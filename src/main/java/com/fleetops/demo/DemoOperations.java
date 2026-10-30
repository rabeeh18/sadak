package com.fleetops.demo;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.time.StartupGate;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.DriverRepository;
import com.fleetops.fleet.DriverStatus;
import com.fleetops.fleet.FleetService;
import com.fleetops.fleet.MaintenanceRecord;
import com.fleetops.fleet.MaintenanceRepository;
import com.fleetops.fleet.Vehicle;
import com.fleetops.fleet.VehicleRepository;
import com.fleetops.fleet.VehicleStatus;
import com.fleetops.fleet.Warehouse;
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.simulation.ClockKeeper;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripRepository;
import com.fleetops.trip.TripService;
import com.fleetops.trip.TripStatus;

/**
 * Keeps the demo operation running: during the working day each warehouse books new shipments
 * and dispatches trips as vehicles come free; vehicles broken down earlier return from the
 * workshop after a few hours; and once the evening's last trip is home, simulated time skips
 * to the next morning. Demo profile only — a real deployment gets its work from people.
 */
@Component
@Profile("demo")
public class DemoOperations {

    private static final Logger log = LoggerFactory.getLogger(DemoOperations.class);
    private static final LocalTime DAY_START = LocalTime.of(7, 30);
    private static final LocalTime LAST_DISPATCH = LocalTime.of(17, 30);
    private static final LocalTime NIGHT = LocalTime.of(19, 30);
    private static final Duration WORKSHOP_TIME = Duration.ofHours(5);

    private final WarehouseRepository warehouses;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final TripRepository trips;
    private final ShipmentRepository shipments;
    private final MaintenanceRepository maintenance;
    private final TripService tripService;
    private final FleetService fleet;
    private final DemoFactory factory;
    private final OperationalClock clock;
    private final ClockKeeper clockKeeper;
    private final StartupGate gate;
    private final TransactionTemplate tx;
    private final ZoneId zone;
    private final Map<Long, Instant> lastDispatch = new HashMap<>();

    public DemoOperations(WarehouseRepository warehouses, VehicleRepository vehicles, DriverRepository drivers,
                          TripRepository trips, ShipmentRepository shipments, MaintenanceRepository maintenance,
                          TripService tripService, FleetService fleet, DemoFactory factory, OperationalClock clock,
                          ClockKeeper clockKeeper, StartupGate gate, PlatformTransactionManager txManager,
                          @Value("${fleet.display-zone}") String zone) {
        this.warehouses = warehouses;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.trips = trips;
        this.shipments = shipments;
        this.maintenance = maintenance;
        this.tripService = tripService;
        this.fleet = fleet;
        this.factory = factory;
        this.clock = clock;
        this.clockKeeper = clockKeeper;
        this.gate = gate;
        this.tx = new TransactionTemplate(txManager);
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void run() {
        if (!gate.isOpen() || clock.paused()) {
            return;
        }
        try {
            ZonedDateTime local = clock.now().atZone(zone);
            returnRepairedVehicles();
            if (!local.toLocalTime().isBefore(DAY_START) && local.toLocalTime().isBefore(LAST_DISPATCH)) {
                dispatch(local);
            } else if (!local.toLocalTime().isBefore(NIGHT) || local.toLocalTime().isBefore(LocalTime.of(5, 0))) {
                skipNightIfQuiet(local);
            }
        } catch (RuntimeException e) {
            log.warn("Demo operations step failed: {}", e.getMessage());
        }
    }

    private void dispatch(ZonedDateTime local) {
        List<Trip> live = trips.findByStatusInOrderByPlannedStartAsc(List.of(TripStatus.PLANNED, TripStatus.IN_PROGRESS));
        Map<Long, Long> liveByWarehouse = new HashMap<>();
        live.forEach(t -> liveByWarehouse.merge(t.getWarehouse().getId(), 1L, Long::sum));
        LocalDate today = local.toLocalDate();
        Instant now = clock.now();

        List<Warehouse> all = new ArrayList<>(warehouses.findByActiveTrueOrderByNameAsc());
        Collections.shuffle(all, factory.random());
        for (Warehouse w : all) {
            int target = w.getCode().equals("LDH") || w.getCode().equals("JUC") ? 3 : 2;
            if (liveByWarehouse.getOrDefault(w.getId(), 0L) >= target) {
                continue;
            }
            Instant last = lastDispatch.get(w.getId());
            if (last != null && Duration.between(last, now).toMinutes() < 25) {
                continue;
            }
            Optional<Vehicle> vehicle = vehicles.findByStatusOrderByRegistrationAsc(VehicleStatus.AVAILABLE).stream()
                .filter(v -> v.getHomeWarehouse().getId().equals(w.getId()) && v.isSimulated()).findFirst();
            Optional<Driver> driver = drivers.findByStatusOrderByFullNameAsc(DriverStatus.AVAILABLE).stream()
                .filter(d -> d.getHomeWarehouse().getId().equals(w.getId()) && d.licenseValidOn(today) && d.getUser() == null)
                .findFirst();
            if (vehicle.isEmpty() || driver.isEmpty()) {
                continue;
            }
            lastDispatch.put(w.getId(), now);
            tx.executeWithoutResult(s -> planTrip(w, vehicle.get(), driver.get(), now));
            return; // one dispatch per step keeps routing calls within the public server's limits
        }
    }

    private void planTrip(Warehouse w, Vehicle vehicle, Driver driver, Instant now) {
        List<DemoData.TownSeed> towns = new ArrayList<>(factory.townsOf(w.getCode()));
        Collections.shuffle(towns, factory.random());
        int townCount = 1 + factory.random().nextInt(2);
        List<Long> ids = new ArrayList<>();
        int load = 0;
        for (DemoData.TownSeed town : towns.subList(0, Math.min(townCount, towns.size()))) {
            int parcels = 2 + factory.random().nextInt(3);
            for (int i = 0; i < parcels; i++) {
                int weight = factory.weight();
                if (load + weight > vehicle.getCapacityKg() * 0.9) {
                    break;
                }
                ids.add(factory.shipment(w, town, now.plus(Duration.ofHours(8)), now.minus(Duration.ofMinutes(90)), weight).getId());
                load += weight;
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Instant start = now.plus(Duration.ofMinutes(3 + factory.random().nextInt(10)));
        Trip trip = tripService.plan(new TripService.PlanRequest(w.getId(), ids, vehicle.getId(), driver.getId(), start, true));
        // About one trip in four is promised tightly, so the risk engine has something to find.
        boolean tight = factory.random().nextInt(4) == 0;
        for (Shipment s : shipments.findByTripIdOrderByIdAsc(trip.getId())) {
            int slack = tight ? 4 + factory.random().nextInt(8) : 25 + factory.random().nextInt(50);
            s.changeDeadline(s.getStop().getPlannedArrival().plus(Duration.ofMinutes(slack)).truncatedTo(ChronoUnit.MINUTES));
        }
        log.info("Demo dispatch: {} from {} with {} shipments{}", trip.getCode(), w.getCode(), ids.size(), tight ? " (tight deadlines)" : "");
    }

    /** Breakdowns from earlier in the day come back from the workshop after a few simulated hours. */
    private void returnRepairedVehicles() {
        Instant now = clock.now();
        for (Vehicle v : vehicles.findByStatusInOrderByRegistrationAsc(List.of(VehicleStatus.BROKEN_DOWN, VehicleStatus.MAINTENANCE))) {
            Optional<MaintenanceRecord> open = maintenance.findByVehicleIdAndEndedAtIsNull(v.getId());
            boolean due = open.map(r -> Duration.between(r.getStartedAt(), now).compareTo(WORKSHOP_TIME) > 0).orElse(true);
            boolean stillOnTrip = trips.findFirstByVehicleIdAndStatusIn(v.getId(), List.of(TripStatus.PLANNED, TripStatus.IN_PROGRESS)).isPresent();
            if (due && !stillOnTrip) {
                fleet.returnToService(v.getId());
            }
        }
    }

    private void skipNightIfQuiet(ZonedDateTime local) {
        boolean anyMoving = trips.findByStatusInOrderByPlannedStartAsc(List.of(TripStatus.IN_PROGRESS, TripStatus.PLANNED)).stream()
            .anyMatch(t -> t.getStatus() == TripStatus.IN_PROGRESS || t.isAutoCompleteStops());
        if (anyMoving) {
            return;
        }
        LocalDate day = local.toLocalTime().isBefore(LocalTime.of(5, 0)) ? local.toLocalDate() : local.toLocalDate().plusDays(1);
        Instant morning = day.atTime(7, 40).atZone(zone).toInstant();
        Duration skip = Duration.between(clock.now(), morning);
        if (!skip.isNegative()) {
            clock.advance(skip);
            clockKeeper.saveControls();
            lastDispatch.clear();
            log.info("Demo: quiet night skipped to {}", morning.atZone(zone));
        }
    }
}
