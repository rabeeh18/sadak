package com.fleetops.demo;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fleetops.common.geo.GeoPoint;
import com.fleetops.common.geo.RouteLine;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.DriverRepository;
import com.fleetops.fleet.Vehicle;
import com.fleetops.fleet.VehicleRepository;
import com.fleetops.fleet.Warehouse;
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.security.Role;
import com.fleetops.security.UserAccount;
import com.fleetops.security.UserAccountRepository;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.shipment.ShipmentStatus;
import com.fleetops.simulation.ClockKeeper;
import com.fleetops.trip.RouteVersion;
import com.fleetops.trip.RouteVersionRepository;
import com.fleetops.trip.StopStatus;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripRepository;
import com.fleetops.trip.TripService;
import com.fleetops.trip.TripStatus;
import com.fleetops.trip.TripStop;

/**
 * Seeds the demo on first start: Punjab warehouses, fleet, drivers and users; eight weeks of
 * completed history; and a live morning of trips already on the road. Runs only in the demo
 * profile and only when the database has no warehouses.
 */
@Component
@Profile("demo")
public class DemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    public static final String DEMO_DRIVER_USERNAME = "driver";

    private final WarehouseRepository warehouses;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final UserAccountRepository users;
    private final ShipmentRepository shipments;
    private final DemoFactory factory;
    private final TripRepository trips;
    private final RouteVersionRepository routes;
    private final TripService tripService;
    private final HistoryGenerator history;
    private final PasswordEncoder encoder;
    private final OperationalClock clock;
    private final ClockKeeper clockKeeper;
    private final TransactionTemplate tx;
    private final ZoneId zone;
    private final String demoPassword;
    private final Random random = new Random(20261021L);

    public DemoSeeder(WarehouseRepository warehouses, VehicleRepository vehicles, DriverRepository drivers,
                      UserAccountRepository users, ShipmentRepository shipments, DemoFactory factory,
                      TripRepository trips, RouteVersionRepository routes, TripService tripService,
                      HistoryGenerator history, PasswordEncoder encoder, OperationalClock clock, ClockKeeper clockKeeper,
                      PlatformTransactionManager txManager, @Value("${fleet.display-zone}") String zone,
                      @Value("${fleet.demo.password:}") String demoPassword) {
        this.warehouses = warehouses;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.users = users;
        this.shipments = shipments;
        this.factory = factory;
        this.trips = trips;
        this.routes = routes;
        this.tripService = tripService;
        this.history = history;
        this.encoder = encoder;
        this.clock = clock;
        this.clockKeeper = clockKeeper;
        this.tx = new TransactionTemplate(txManager);
        this.zone = ZoneId.of(zone);
        this.demoPassword = demoPassword;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(5)
    public void seed() {
        if (warehouses.count() > 0) {
            return;
        }
        long started = System.currentTimeMillis();
        alignClockToWorkingDay();
        // One transaction: a failure part-way leaves an empty database, so the next start seeds again.
        tx.executeWithoutResult(s -> {
            Map<String, Warehouse> byCode = seedReference();
            history.generate(clock.now(), 56);
            seedUnplannedShipments(byCode);
            seedLiveTrips(byCode);
        });
        log.info("Demo data seeded in {} s", (System.currentTimeMillis() - started) / 1000);
    }

    /** The demo starts at 09:40 local time so the day's operation is under way. */
    private void alignClockToWorkingDay() {
        ZonedDateTime local = clock.now().atZone(zone);
        LocalTime t = local.toLocalTime();
        if (t.isAfter(LocalTime.of(8, 0)) && t.isBefore(LocalTime.of(15, 0))) {
            return;
        }
        LocalDate day = t.isBefore(LocalTime.of(8, 0)) ? local.toLocalDate() : local.toLocalDate().plusDays(1);
        Instant target = day.atTime(9, 40).atZone(zone).toInstant();
        clock.advance(Duration.between(clock.now(), target));
        clockKeeper.saveControls();
    }

    // ------------------------------------------------------------------ reference data

    private Map<String, Warehouse> seedReference() {
        String password = demoPassword == null || demoPassword.isBlank() ? generatedPassword() : demoPassword;
        String hash = encoder.encode(password);
        users.save(new UserAccount("admin", hash, "Asha Mehra", EnumSet.of(Role.ADMIN, Role.DISPATCHER)));
        users.save(new UserAccount("dispatcher", hash, "Simran Kaur", EnumSet.of(Role.DISPATCHER)));
        users.save(new UserAccount("dispatcher2", hash, "Vikram Bedi", EnumSet.of(Role.DISPATCHER)));
        UserAccount driverUser = users.save(new UserAccount(DEMO_DRIVER_USERNAME, hash, "Gurpreet Singh", EnumSet.of(Role.DRIVER)));

        Map<String, Warehouse> byCode = new HashMap<>();
        for (DemoData.WarehouseSeed w : DemoData.WAREHOUSES) {
            byCode.put(w.code(), warehouses.save(new Warehouse(w.code(), w.name(), w.city(), w.address(), w.lat(), w.lng())));
        }

        String[] series = {"GX", "DK", "CF", "HB", "AT", "MZ", "KR", "BS"};
        int n = 0;
        for (DemoData.WarehouseSeed w : DemoData.WAREHOUSES) {
            int count = w.code().equals("LDH") || w.code().equals("JUC") ? 5 : 4;
            for (int i = 0; i < count; i++) {
                DemoData.VehicleType type = DemoData.VEHICLE_TYPES.get((n + i) % DemoData.VEHICLE_TYPES.size());
                String reg = w.regPrefix() + " " + series[(n * 3 + i) % series.length] + " " + String.format("%04d", 1000 + random.nextInt(8999));
                vehicles.save(new Vehicle(reg, type.name(), type.capacityKg(), byCode.get(w.code())));
            }
            n++;
        }

        LocalDate today = LocalDate.ofInstant(clock.now(), zone);
        int d = 0;
        for (String name : DemoData.DRIVER_NAMES) {
            DemoData.WarehouseSeed w = DemoData.WAREHOUSES.get(d % DemoData.WAREHOUSES.size());
            LocalDate expiry = switch (name) {
                case "Iqbal Singh" -> today.minusDays(24);          // expired: shows the eligibility check
                case "Satnam Virk", "Ravinder Kumar" -> today.plusDays(19); // expiring soon
                default -> today.plusYears(1 + random.nextInt(6)).plusDays(random.nextInt(300));
            };
            String license = w.regPrefix().replace(" ", "") + " " + (2008 + random.nextInt(15)) + " " + String.format("%07d", random.nextInt(9_999_999));
            String phone = "+91 9" + String.format("%09d", random.nextInt(999_999_999));
            Driver driver = new Driver(name, phone, license, expiry, byCode.get(w.code()));
            if (name.equals("Gurpreet Singh")) {
                driver.setUser(driverUser);
            }
            drivers.save(driver);
            d++;
        }
        return byCode;
    }

    private String generatedPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        log.warn("FLEET_DEMO_PASSWORD is not set; demo users were given a generated password: {}", sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ live morning

    private void seedUnplannedShipments(Map<String, Warehouse> byCode) {
        Instant now = clock.now();
        Map<String, Integer> counts = Map.of("LDH", 12, "JUC", 9, "MOH", 6, "PTA", 4);
        counts.forEach((code, count) -> {
            List<DemoData.TownSeed> towns = DemoData.TOWNS.stream().filter(t -> t.warehouseCode().equals(code)).toList();
            for (int i = 0; i < count; i++) {
                DemoData.TownSeed town = towns.get(random.nextInt(towns.size()));
                Instant deadline = now.plus(Duration.ofMinutes(240 + random.nextInt(360))).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
                newShipment(byCode.get(code), town, deadline, now.minus(Duration.ofMinutes(30 + random.nextInt(120))), 40 + random.nextInt(360));
            }
        });
    }

    private record LivePlan(String warehouse, List<String> towns, int stopsPerTown, int startedMinutesAgo,
                            int stopsDone, int slackMin, String driverName, boolean autoComplete) {
    }

    private void seedLiveTrips(Map<String, Warehouse> byCode) {
        List<LivePlan> plans = List.of(
            new LivePlan("LDH", List.of("Jagraon", "Moga"), 2, 38, 1, 9, "Manpreet Gill", true),
            new LivePlan("JUC", List.of("Phagwara", "Nawanshahr"), 2, 22, 0, 50, "Harjit Kaur", true),
            new LivePlan("ASR", List.of("Jandiala Guru", "Rayya", "Tarn Taran"), 1, 12, 0, 45, "Kulwant Brar", true),
            new LivePlan("PTA", List.of("Rajpura", "Sirhind"), 2, 34, 1, 40, "Rajvir Sidhu", true),
            new LivePlan("BTI", List.of("Rampura Phul", "Mansa"), 2, 6, 0, 55, "Amandeep Sandhu", true),
            new LivePlan("MOH", List.of("Kharar", "Kurali", "Ropar"), 1, 31, 1, 35, "Sukhwinder Dhillon", true),
            new LivePlan("LDH", List.of("Doraha", "Khanna"), 2, 16, 0, 30, "Baljinder Singh", true),
            new LivePlan("JUC", List.of("Kapurthala", "Kartarpur"), 2, -15, 0, 60, "Jaswinder Kaur", true),
            new LivePlan("ASR", List.of("Batala", "Gurdaspur"), 2, -40, 0, 70, "Navdeep Cheema", true),
            new LivePlan("LDH", List.of("Samrala", "Payal"), 2, -8, 0, 60, "Gurpreet Singh", false));

        Instant now = clock.now();
        Set<Long> usedVehicles = new java.util.HashSet<>();
        for (LivePlan p : plans) {
            Warehouse w = byCode.get(p.warehouse());
            Driver driver = drivers.findAllByOrderByFullNameAsc().stream().filter(d -> d.getFullName().equals(p.driverName())).findFirst().orElseThrow();
            Vehicle vehicle = vehicles.findAllByOrderByRegistrationAsc().stream()
                .filter(v -> v.getHomeWarehouse().getId().equals(w.getId()) && !usedVehicles.contains(v.getId()))
                .findFirst().orElseThrow();
            usedVehicles.add(vehicle.getId());

            List<Long> ids = new ArrayList<>();
            int load = 0;
            for (String townName : p.towns()) {
                DemoData.TownSeed town = DemoData.TOWNS.stream().filter(t -> t.town().equals(townName)).findFirst().orElseThrow();
                for (int s = 0; s < p.stopsPerTown(); s++) {
                    int perStop = 1 + random.nextInt(2);
                    for (int k = 0; k < perStop; k++) {
                        int weight = 40 + random.nextInt(260);
                        if (load + weight > vehicle.getCapacityKg() * 0.9) {
                            break;
                        }
                        Shipment sh = newShipment(w, town, now.plus(Duration.ofHours(8)), now.minus(Duration.ofHours(14)), weight);
                        ids.add(sh.getId());
                        load += weight;
                    }
                }
            }
            Instant plannedStart = now.minus(Duration.ofMinutes(p.startedMinutesAgo()));
            Trip trip = tripService.plan(new TripService.PlanRequest(w.getId(), ids, vehicle.getId(), driver.getId(),
                plannedStart, p.autoComplete()));
            // Promised times: planned arrival plus a slack; the first trip is deliberately tight.
            for (Shipment sh : shipments.findByTripIdOrderByIdAsc(trip.getId())) {
                Instant arrival = sh.getStop().getPlannedArrival();
                sh.changeDeadline(arrival.plus(Duration.ofMinutes(p.slackMin() + random.nextInt(10))).truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
            }
            if (p.startedMinutesAgo() > 0) {
                tripService.start(trip.getId());
                rewindStart(trip, plannedStart, p.stopsDone(), now);
            }
        }
    }

    /** Makes a trip look as if it left earlier: completes its first stops and places the vehicle en route. */
    private void rewindStart(Trip trip, Instant departedAt, int stopsDone, Instant now) {
        Trip t = trips.findById(trip.getId()).orElseThrow();
        t.restoreHistory(TripStatus.IN_PROGRESS, departedAt, null, t.getPlannedEnd());
        RouteVersion rv = routes.findByTripIdAndActiveTrue(t.getId()).orElseThrow();
        RouteLine line = RouteLine.fromEncoded(rv.getGeometry());
        List<Integer> legDist = rv.legDistances();
        double scale = line.length() / legDist.stream().mapToDouble(Integer::doubleValue).sum();

        for (int i = 0; i < stopsDone && i < t.getStops().size(); i++) {
            TripStop stop = t.getStops().get(i);
            Instant arrived = stop.getPlannedArrival().isBefore(now) ? stop.getPlannedArrival() : now.minus(Duration.ofMinutes(12));
            stop.restoreHistory(StopStatus.COMPLETED, arrived, arrived.plus(Duration.ofMinutes(stop.getServiceMin())));
            for (Shipment sh : shipments.findByStopId(stop.getId())) {
                sh.restoreHistory(ShipmentStatus.DELIVERED, arrived.plus(Duration.ofMinutes(3)), null, 1, false);
            }
        }
        // Position: part-way along the next leg, in proportion to the time elapsed since the last departure.
        int leg = Math.min(stopsDone, legDist.size() - 1);
        double legStart = 0;
        for (int i = 0; i < leg; i++) {
            legStart += legDist.get(i) * scale;
        }
        Instant legDeparture = stopsDone == 0 ? departedAt : t.getStops().get(stopsDone - 1).getActualDeparture();
        double legSeconds = rv.legDurations().get(leg);
        double fraction = Math.max(0.05, Math.min(0.8, Duration.between(legDeparture, now).toSeconds() / Math.max(60, legSeconds)));
        GeoPoint pos = line.pointAt(legStart + legDist.get(leg) * scale * fraction);
        t.getVehicle().recordPosition(pos, now);
    }

    private Shipment newShipment(Warehouse origin, DemoData.TownSeed town, Instant deadline, Instant createdAt, int weightKg) {
        return factory.shipment(origin, town, deadline, createdAt, weightKg);
    }
}
