package com.fleetops.incident;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.common.geo.GeoPoint;
import com.fleetops.common.geo.GridReference;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.DriverRepository;
import com.fleetops.fleet.DriverStatus;
import com.fleetops.fleet.Vehicle;
import com.fleetops.fleet.VehicleRepository;
import com.fleetops.fleet.VehicleStatus;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.shipment.ShipmentStatus;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripService;

/**
 * Suggests replacement vehicle and driver pairs for a disrupted trip. Filters on hard rules
 * (available, enough capacity, valid licence) and ranks by how soon the vehicle could reach the
 * trip's current position. Every suggestion says why it was made.
 */
@Service
public class RecommendationService {

    private static final double ROAD_FACTOR = 1.3;
    private static final double AVERAGE_KPH = 40;

    private final TripService trips;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final ShipmentRepository shipments;
    private final OperationalClock clock;
    private final ZoneId zone;

    public RecommendationService(TripService trips, VehicleRepository vehicles, DriverRepository drivers,
                                 ShipmentRepository shipments, OperationalClock clock,
                                 @Value("${fleet.display-zone}") String zone) {
        this.trips = trips;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.shipments = shipments;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public record Candidate(long vehicleId, String registration, String vehicleType, int capacityKg,
                            long driverId, String driverName, double distanceKm, int reachMinutes,
                            String grid, List<String> reasons) {
    }

    public record Recommendation(int remainingWeightKg, String site, List<Candidate> candidates, List<String> excluded) {
    }

    @Transactional(readOnly = true)
    public Recommendation forTrip(long tripId) {
        Trip trip = trips.get(tripId);
        GeoPoint site = trip.getVehicle().lastPosition();
        int remaining = shipments.findByTripIdInAndStatusIn(List.of(tripId), Set.of(ShipmentStatus.PLANNED, ShipmentStatus.OUT_FOR_DELIVERY))
            .stream().mapToInt(Shipment::getWeightKg).sum();
        LocalDate today = LocalDate.ofInstant(clock.now(), zone);

        List<Driver> freeDrivers = drivers.findByStatusOrderByFullNameAsc(DriverStatus.AVAILABLE).stream()
            .filter(d -> d.licenseValidOn(today)).toList();
        List<String> excluded = new ArrayList<>();
        List<Candidate> out = new ArrayList<>();
        for (Vehicle v : vehicles.findByStatusOrderByRegistrationAsc(VehicleStatus.AVAILABLE)) {
            if (v.getId().equals(trip.getVehicle().getId())) {
                continue;
            }
            if (v.getCapacityKg() < remaining) {
                excluded.add(v.getRegistration() + ": capacity " + v.getCapacityKg() + " kg < " + remaining + " kg remaining");
                continue;
            }
            Optional<Driver> driver = freeDrivers.stream()
                .filter(d -> out.stream().noneMatch(c -> c.driverId() == d.getId()))
                .min(Comparator.comparingInt((Driver d) -> d.getHomeWarehouse().getId().equals(v.getHomeWarehouse().getId()) ? 0 : 1)
                    .thenComparing(Driver::getFullName));
            if (driver.isEmpty()) {
                excluded.add(v.getRegistration() + ": no available driver with a valid licence");
                continue;
            }
            double km = v.lastPosition().distanceTo(site) / 1000 * ROAD_FACTOR;
            int minutes = (int) Math.round(km / AVERAGE_KPH * 60);
            List<String> reasons = new ArrayList<>();
            reasons.add(String.format("About %.0f km away, roughly %d min to reach the site", km, minutes));
            reasons.add("Capacity " + v.getCapacityKg() + " kg covers the remaining " + remaining + " kg");
            Driver d = driver.get();
            reasons.add(d.getFullName() + (d.getHomeWarehouse().getId().equals(v.getHomeWarehouse().getId())
                ? " is based at the same warehouse" : " is available") + "; licence valid to " + d.getLicenseExpiry());
            out.add(new Candidate(v.getId(), v.getRegistration(), v.getType(), v.getCapacityKg(), d.getId(), d.getFullName(),
                Math.round(km * 10) / 10.0, minutes, GridReference.of(v.lastPosition()), reasons));
        }
        out.sort(Comparator.comparingInt(Candidate::reachMinutes));
        return new Recommendation(remaining, GridReference.of(site), out.stream().limit(5).toList(), excluded);
    }
}
