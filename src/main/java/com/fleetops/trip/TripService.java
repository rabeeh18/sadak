package com.fleetops.trip;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.audit.AuditFields;
import com.fleetops.audit.AuditService;
import com.fleetops.common.geo.GeoPoint;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.DriverRepository;
import com.fleetops.fleet.DriverStatus;
import com.fleetops.fleet.FleetService;
import com.fleetops.fleet.Vehicle;
import com.fleetops.fleet.VehicleRepository;
import com.fleetops.fleet.VehicleStatus;
import com.fleetops.fleet.Warehouse;
import com.fleetops.history.StatusHistoryService;
import com.fleetops.routing.RoutePlan;
import com.fleetops.routing.RoutingService;
import com.fleetops.security.CurrentUser;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.shipment.ShipmentStatus;

/**
 * Trip lifecycle: planning, start, stop outcomes, breakdown, reassignment, cancellation.
 * Every operation that touches several rows is one transaction; vehicle and driver rows are
 * locked before allocation and a partial unique index backs the "one live trip each" rule.
 */
@Service
public class TripService {

    public static final int TRANSFER_SERVICE_MIN = 20;
    private static final List<TripStatus> LIVE = List.of(TripStatus.PLANNED, TripStatus.IN_PROGRESS);

    private final TripRepository trips;
    private final TripStopRepository stops;
    private final RouteVersionRepository routes;
    private final TripAssignmentRepository assignments;
    private final DeliveryProofRepository proofs;
    private final ShipmentRepository shipments;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final FleetService fleet;
    private final RoutingService routing;
    private final StatusHistoryService history;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final OperationalClock clock;
    private final ZoneId zone;

    public TripService(TripRepository trips, TripStopRepository stops, RouteVersionRepository routes,
                       TripAssignmentRepository assignments, DeliveryProofRepository proofs,
                       ShipmentRepository shipments, VehicleRepository vehicles, DriverRepository drivers,
                       FleetService fleet, RoutingService routing, StatusHistoryService history, AuditService audit,
                       ApplicationEventPublisher events, OperationalClock clock,
                       @Value("${fleet.display-zone}") String zone) {
        this.trips = trips;
        this.stops = stops;
        this.routes = routes;
        this.assignments = assignments;
        this.proofs = proofs;
        this.shipments = shipments;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.fleet = fleet;
        this.routing = routing;
        this.history = history;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public record PlanRequest(long warehouseId, Collection<Long> shipmentIds, long vehicleId, long driverId,
                              Instant plannedStart, boolean autoCompleteStops) {
    }

    // ------------------------------------------------------------------ planning

    /**
     * Builds a trip from unassigned shipments: groups them into stops by destination, orders the
     * stops nearest-first from the warehouse, fetches the road route and sets planned arrivals.
     */
    @Transactional
    public Trip plan(PlanRequest req) {
        if (req.shipmentIds() == null || req.shipmentIds().isEmpty()) {
            throw new BusinessRuleException("Select at least one shipment");
        }
        Warehouse warehouse = fleet.warehouse(req.warehouseId());
        Vehicle vehicle = vehicles.lockById(req.vehicleId()).orElseThrow(() -> new NotFoundException("Vehicle", req.vehicleId()));
        Driver driver = drivers.lockById(req.driverId()).orElseThrow(() -> new NotFoundException("Driver", req.driverId()));
        checkAllocatable(vehicle, driver, req.plannedStart());

        List<Shipment> selected = shipments.findAllById(req.shipmentIds());
        if (selected.size() != req.shipmentIds().size()) {
            throw new BusinessRuleException("Some selected shipments no longer exist");
        }
        for (Shipment s : selected) {
            if (s.getStatus() != ShipmentStatus.CREATED) {
                throw new BusinessRuleException("Shipment " + s.getTrackingNo() + " is " + s.getStatus().label().toLowerCase());
            }
            if (!s.getOriginWarehouse().getId().equals(warehouse.getId())) {
                throw new BusinessRuleException("Shipment " + s.getTrackingNo() + " ships from another warehouse");
            }
        }
        int weight = selected.stream().mapToInt(Shipment::getWeightKg).sum();
        if (weight > vehicle.getCapacityKg()) {
            throw new BusinessRuleException("Load " + weight + " kg exceeds " + vehicle.getRegistration()
                + " capacity of " + vehicle.getCapacityKg() + " kg");
        }

        Instant now = clock.now();
        Trip trip = new Trip("TR-" + trips.nextCodeNumber(), warehouse, vehicle, driver,
            req.plannedStart() != null ? req.plannedStart() : now, CurrentUser.username(), now);
        trip.setTotalWeightKg(weight);
        trip.setAutoCompleteStops(req.autoCompleteStops());

        Map<String, List<Shipment>> byDestination = groupByDestination(selected);
        List<List<Shipment>> ordered = orderNearestFirst(warehouse.location(), byDestination.values());
        for (List<Shipment> group : ordered) {
            Shipment first = group.get(0);
            int serviceMin = 8 + 2 * group.size();
            trip.addStop(new TripStop(TripStop.Kind.DELIVERY, first.getDestName(), first.getDestAddress(),
                first.destination(), serviceMin));
        }
        trips.save(trip);

        List<GeoPoint> waypoints = new ArrayList<>();
        waypoints.add(warehouse.location());
        trip.getStops().forEach(s -> waypoints.add(s.location()));
        RoutePlan plan = routing.route(waypoints);
        RouteVersion route = routes.save(new RouteVersion(trip, 1, plan.encodedGeometry(), plan.distanceM(), plan.durationS(),
            plan.legDistancesM(), plan.legDurationsS(), plan.estimated(), "Initial plan", CurrentUser.username(), now));
        applySchedule(trip, route, trip.getPlannedStart(), trip.getStops());

        for (int i = 0; i < ordered.size(); i++) {
            TripStop stop = trip.getStops().get(i);
            for (Shipment s : ordered.get(i)) {
                ShipmentStatus old = s.transition(ShipmentStatus.PLANNED);
                s.assignTo(trip, stop);
                history.record(StatusHistoryService.SHIPMENT, s.getId(), old, ShipmentStatus.PLANNED, "Planned on " + trip.getCode());
            }
        }

        reserve(vehicle, driver);
        assignments.save(new TripAssignment(trip, vehicle, driver, "Initial assignment", CurrentUser.username(), now));
        history.record(StatusHistoryService.TRIP, trip.getId(), null, TripStatus.PLANNED, "Planned");
        audit.record("TRIP_PLANNED", "TRIP", trip.getId(),
            trip.getCode() + " planned: " + trip.getStops().size() + " stops, " + weight + " kg on " + vehicle.getRegistration(),
            null, AuditFields.of("vehicle", vehicle.getRegistration(), "driver", driver.getFullName(),
                "shipments", selected.size(), "routeEstimated", plan.estimated()));
        events.publishEvent(new TripEvents.TripChanged(trip.getId()));
        return trip;
    }

    private void checkAllocatable(Vehicle vehicle, Driver driver, Instant when) {
        if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
            throw new BusinessRuleException(vehicle.getRegistration() + " is " + vehicle.getStatus().label().toLowerCase());
        }
        if (driver.getStatus() != DriverStatus.AVAILABLE) {
            throw new BusinessRuleException(driver.getFullName() + " is " + driver.getStatus().label().toLowerCase());
        }
        LocalDate day = LocalDate.ofInstant(when != null ? when : clock.now(), zone);
        if (!driver.licenseValidOn(day)) {
            throw new BusinessRuleException(driver.getFullName() + "'s licence expired on " + driver.getLicenseExpiry());
        }
    }

    private void reserve(Vehicle vehicle, Driver driver) {
        vehicle.setStatus(VehicleStatus.ON_TRIP);
        driver.setStatus(DriverStatus.ON_TRIP);
    }

    private static Map<String, List<Shipment>> groupByDestination(List<Shipment> list) {
        Map<String, List<Shipment>> groups = new LinkedHashMap<>();
        for (Shipment s : list) {
            String key = String.format("%.4f,%.4f", s.getDestLat(), s.getDestLng());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        return groups;
    }

    /** Nearest-neighbour ordering: simple, predictable, and good enough for a dozen stops. */
    static List<List<Shipment>> orderNearestFirst(GeoPoint origin, Collection<List<Shipment>> groups) {
        List<List<Shipment>> remaining = new ArrayList<>(groups);
        List<List<Shipment>> ordered = new ArrayList<>();
        GeoPoint current = origin;
        while (!remaining.isEmpty()) {
            GeoPoint from = current;
            List<Shipment> next = remaining.stream()
                .min(Comparator.comparingDouble(g -> from.distanceTo(g.get(0).destination())))
                .orElseThrow();
            remaining.remove(next);
            ordered.add(next);
            current = next.get(0).destination();
        }
        return ordered;
    }

    /** Planned arrivals: departure time plus cumulative leg durations and service times. */
    private void applySchedule(Trip trip, RouteVersion route, Instant departure, List<TripStop> routeStops) {
        List<Integer> legDurations = route.legDurations();
        Instant t = departure;
        for (int i = 0; i < routeStops.size(); i++) {
            TripStop stop = routeStops.get(i);
            t = t.plusSeconds(legDurations.get(i));
            if (stop.getPlannedArrival() == null) {
                stop.setPlannedArrival(t);
            }
            stop.setEta(t);
            stop.placeOnRoute(route.getVersionNo(), i);
            t = t.plus(Duration.ofMinutes(stop.getServiceMin()));
        }
        if (trip.getPlannedEnd() == null || route.getVersionNo() == 1) {
            trip.setPlannedEnd(t);
        }
    }

    // ------------------------------------------------------------------ execution

    @Transactional
    public void start(long tripId) {
        Trip trip = lock(tripId);
        Instant now = clock.now();
        trip.start(now);
        for (Shipment s : shipments.findByTripIdOrderByIdAsc(tripId)) {
            if (s.getStatus() == ShipmentStatus.PLANNED) {
                s.transition(ShipmentStatus.OUT_FOR_DELIVERY);
                history.record(StatusHistoryService.SHIPMENT, s.getId(), ShipmentStatus.PLANNED, ShipmentStatus.OUT_FOR_DELIVERY,
                    "Departed on " + trip.getCode());
            }
        }
        history.record(StatusHistoryService.TRIP, tripId, TripStatus.PLANNED, TripStatus.IN_PROGRESS, "Started");
        audit.record("TRIP_STARTED", "TRIP", tripId, trip.getCode() + " started from " + trip.getWarehouse().getName());
        events.publishEvent(new TripEvents.RouteActivated(tripId));
        events.publishEvent(new TripEvents.TripChanged(tripId));
    }

    @Transactional
    public void arrive(long tripId, long stopId) {
        Trip trip = lock(tripId);
        requireInProgress(trip);
        TripStop stop = stopOf(trip, stopId);
        if (stop.getStatus() != StopStatus.PENDING) {
            return; // geofence and driver may both report an arrival; the first one wins
        }
        stop.arrive(clock.now());
        history.record(StatusHistoryService.STOP, stopId, StopStatus.PENDING, StopStatus.ARRIVED, null);
        events.publishEvent(new TripEvents.StopArrived(tripId, stopId));
        events.publishEvent(new TripEvents.TripChanged(tripId));
    }

    /** Completes a stop with proof of delivery; completes the trip when it was the last stop. */
    @Transactional
    public void completeStop(long tripId, long stopId, String recipientName, String note, GeoPoint position) {
        Trip trip = lock(tripId);
        requireInProgress(trip);
        TripStop stop = stopOf(trip, stopId);
        if (recipientName == null || recipientName.isBlank()) {
            throw new BusinessRuleException("Enter the name of the person who received the delivery");
        }
        Instant now = clock.now();
        StopStatus old = stop.getStatus();
        stop.finish(StopStatus.COMPLETED, now);
        proofs.save(new DeliveryProof(stop, recipientName.trim(), blankToNull(note),
            position == null ? null : position.lat(), position == null ? null : position.lng(), CurrentUser.username(), now));
        for (Shipment s : shipments.findByStopId(stopId)) {
            if (s.getStatus() == ShipmentStatus.OUT_FOR_DELIVERY) {
                s.markDelivered(now);
                history.record(StatusHistoryService.SHIPMENT, s.getId(), ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERED,
                    "Received by " + recipientName.trim());
            }
        }
        history.record(StatusHistoryService.STOP, stopId, old, StopStatus.COMPLETED, null);
        if (stop.getKind() == TripStop.Kind.DELIVERY) {
            audit.record("DELIVERY_COMPLETED", "STOP", stopId, trip.getCode() + " stop " + stop.getSeq() + " delivered at " + stop.getName());
        }
        events.publishEvent(new TripEvents.StopCompleted(tripId, stopId));
        finishIfDone(trip, now);
        events.publishEvent(new TripEvents.TripChanged(tripId));
    }

    /** Completes a transfer stop (cargo moved from a broken-down vehicle). */
    @Transactional
    public void completeTransfer(long tripId, long stopId) {
        Trip trip = lock(tripId);
        requireInProgress(trip);
        TripStop stop = stopOf(trip, stopId);
        if (stop.getKind() != TripStop.Kind.TRANSFER) {
            throw new BusinessRuleException("Stop " + stop.getSeq() + " is not a transfer stop");
        }
        StopStatus old = stop.getStatus();
        stop.finish(StopStatus.COMPLETED, clock.now());
        history.record(StatusHistoryService.STOP, stopId, old, StopStatus.COMPLETED, "Cargo transferred");
        events.publishEvent(new TripEvents.StopCompleted(tripId, stopId));
        finishIfDone(trip, clock.now());
        events.publishEvent(new TripEvents.TripChanged(tripId));
    }

    @Transactional
    public void failStop(long tripId, long stopId, String reason) {
        Trip trip = lock(tripId);
        requireInProgress(trip);
        TripStop stop = stopOf(trip, stopId);
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Give a reason for the failed delivery");
        }
        Instant now = clock.now();
        StopStatus old = stop.getStatus();
        stop.finish(StopStatus.FAILED, now);
        for (Shipment s : shipments.findByStopId(stopId)) {
            if (s.getStatus() == ShipmentStatus.OUT_FOR_DELIVERY) {
                s.markFailed(reason.trim());
                history.record(StatusHistoryService.SHIPMENT, s.getId(), ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.FAILED, reason.trim());
            }
        }
        history.record(StatusHistoryService.STOP, stopId, old, StopStatus.FAILED, reason.trim());
        audit.record("DELIVERY_FAILED", "STOP", stopId, trip.getCode() + " stop " + stop.getSeq() + " failed: " + reason.trim());
        events.publishEvent(new TripEvents.StopFailed(tripId, stopId, reason.trim()));
        finishIfDone(trip, now);
        events.publishEvent(new TripEvents.TripChanged(tripId));
    }

    private void finishIfDone(Trip trip, Instant now) {
        if (!trip.allStopsDone()) {
            return;
        }
        trip.complete(now);
        release(trip.getVehicle(), trip.getDriver());
        history.record(StatusHistoryService.TRIP, trip.getId(), TripStatus.IN_PROGRESS, TripStatus.COMPLETED, "All stops done");
        audit.record("TRIP_COMPLETED", "TRIP", trip.getId(), trip.getCode() + " completed");
        events.publishEvent(new TripEvents.TripEnded(trip.getId()));
    }

    private void release(Vehicle vehicle, Driver driver) {
        if (vehicle.getStatus() == VehicleStatus.ON_TRIP) {
            vehicle.setStatus(VehicleStatus.AVAILABLE);
        }
        if (driver.getStatus() == DriverStatus.ON_TRIP) {
            driver.setStatus(DriverStatus.AVAILABLE);
        }
    }

    @Transactional
    public void reportBreakdown(long tripId, String note) {
        Trip trip = lock(tripId);
        requireInProgress(trip);
        Vehicle vehicle = vehicles.lockById(trip.getVehicle().getId()).orElseThrow();
        if (vehicle.getStatus() == VehicleStatus.BROKEN_DOWN) {
            return;
        }
        VehicleStatus old = vehicle.getStatus();
        vehicle.setStatus(VehicleStatus.BROKEN_DOWN);
        String reason = note == null || note.isBlank() ? "Breakdown reported during " + trip.getCode() : note.trim();
        fleet.openBreakdownRecord(vehicle, reason);
        audit.record("VEHICLE_BROKE_DOWN", "VEHICLE", vehicle.getId(), vehicle.getRegistration() + " broke down on " + trip.getCode(),
            AuditFields.of("status", old), AuditFields.of("status", VehicleStatus.BROKEN_DOWN));
        events.publishEvent(new TripEvents.VehicleBrokeDown(tripId, vehicle.getId(), reason));
        events.publishEvent(new TripEvents.TripChanged(tripId));
    }

    @Transactional
    public void adjustDelay(long tripId, int minutes, String note) {
        Trip trip = lock(tripId);
        if (!trip.getStatus().isLive()) {
            throw new BusinessRuleException(trip.getCode() + " is no longer live");
        }
        int old = trip.getDelayAdjustMin();
        trip.adjustDelay(Math.max(-120, Math.min(600, minutes)));
        audit.record("ETA_ADJUSTED", "TRIP", tripId, trip.getCode() + " ETA adjusted by " + minutes + " min"
            + (note == null || note.isBlank() ? "" : ": " + note.trim()),
            AuditFields.of("delayAdjustMin", old), AuditFields.of("delayAdjustMin", trip.getDelayAdjustMin()));
        events.publishEvent(new TripEvents.TripChanged(tripId));
    }

    // ------------------------------------------------------------------ reassignment and route changes

    /**
     * Moves the rest of a trip to another vehicle (and optionally driver). Completed stops stay
     * as they were. If the current vehicle broke down, the new route first goes to the breakdown
     * site to transfer the cargo, then continues through the remaining stops.
     */
    @Transactional
    public void reassign(long tripId, long newVehicleId, long newDriverId, String reason) {
        Trip trip = lock(tripId);
        if (!trip.getStatus().isLive()) {
            throw new BusinessRuleException(trip.getCode() + " is no longer live");
        }
        Vehicle oldVehicle = vehicles.lockById(trip.getVehicle().getId()).orElseThrow();
        Driver oldDriver = drivers.lockById(trip.getDriver().getId()).orElseThrow();
        boolean sameVehicle = oldVehicle.getId() == newVehicleId;
        boolean sameDriver = oldDriver.getId() == newDriverId;
        if (sameVehicle && sameDriver) {
            throw new BusinessRuleException("Choose a different vehicle or driver");
        }
        Vehicle newVehicle = sameVehicle ? oldVehicle : vehicles.lockById(newVehicleId).orElseThrow(() -> new NotFoundException("Vehicle", newVehicleId));
        Driver newDriver = sameDriver ? oldDriver : drivers.lockById(newDriverId).orElseThrow(() -> new NotFoundException("Driver", newDriverId));
        if (!sameVehicle && newVehicle.getStatus() != VehicleStatus.AVAILABLE) {
            throw new BusinessRuleException(newVehicle.getRegistration() + " is " + newVehicle.getStatus().label().toLowerCase());
        }
        if (!sameDriver) {
            if (newDriver.getStatus() != DriverStatus.AVAILABLE) {
                throw new BusinessRuleException(newDriver.getFullName() + " is " + newDriver.getStatus().label().toLowerCase());
            }
            if (!newDriver.licenseValidOn(LocalDate.ofInstant(clock.now(), zone))) {
                throw new BusinessRuleException(newDriver.getFullName() + "'s licence has expired");
            }
        }
        int remainingWeight = shipments.findByTripIdInAndStatusIn(List.of(tripId),
                Set.of(ShipmentStatus.PLANNED, ShipmentStatus.OUT_FOR_DELIVERY))
            .stream().mapToInt(Shipment::getWeightKg).sum();
        if (remainingWeight > newVehicle.getCapacityKg()) {
            throw new BusinessRuleException("Remaining load " + remainingWeight + " kg exceeds " + newVehicle.getRegistration()
                + " capacity of " + newVehicle.getCapacityKg() + " kg");
        }

        Instant now = clock.now();
        String why = reason == null || reason.isBlank() ? "Reassigned by dispatcher" : reason.trim();
        boolean brokenDown = oldVehicle.getStatus() == VehicleStatus.BROKEN_DOWN;
        boolean inProgress = trip.getStatus() == TripStatus.IN_PROGRESS;
        GeoPoint breakdownSite = oldVehicle.lastPosition();

        // Release the old resources first: the partial unique index allows one live trip each.
        if (!sameVehicle && oldVehicle.getStatus() == VehicleStatus.ON_TRIP) {
            oldVehicle.setStatus(VehicleStatus.AVAILABLE);
        }
        if (!sameDriver) {
            oldDriver.setStatus(DriverStatus.AVAILABLE);
        }
        trip.reassign(newVehicle, newDriver);
        reserve(newVehicle, newDriver);
        trips.flush();

        assignments.save(new TripAssignment(trip, newVehicle, newDriver, why, CurrentUser.username(), now));
        audit.record("TRIP_REASSIGNED", "TRIP", tripId, trip.getCode() + " reassigned to " + newVehicle.getRegistration()
                + " / " + newDriver.getFullName() + ": " + why,
            AuditFields.of("vehicle", oldVehicle.getRegistration(), "driver", oldDriver.getFullName()),
            AuditFields.of("vehicle", newVehicle.getRegistration(), "driver", newDriver.getFullName()));

        if (inProgress && !sameVehicle) {
            GeoPoint origin = newVehicle.lastPosition();
            rebuildRoute(trip, origin, brokenDown ? breakdownSite : null, "Reassigned: " + why);
        } else if (!inProgress && !sameVehicle) {
            // A planned trip simply departs from the warehouse with the new vehicle; the route is unchanged.
            newVehicle.recordPosition(trip.getWarehouse().location(), now);
        }
        events.publishEvent(new TripEvents.TripChanged(tripId));
    }

    /** Re-plans the remaining stops from the vehicle's current position, e.g. after a detour. */
    @Transactional
    public void recalculateRoute(long tripId, String reason) {
        Trip trip = lock(tripId);
        requireInProgress(trip);
        rebuildRoute(trip, trip.getVehicle().lastPosition(), null,
            reason == null || reason.isBlank() ? "Recalculated from current position" : reason.trim());
        events.publishEvent(new TripEvents.TripChanged(tripId));
    }

    private void rebuildRoute(Trip trip, GeoPoint origin, GeoPoint transferSite, String reason) {
        List<TripStop> open = trip.getStops().stream().filter(s -> !s.getStatus().isDone()).collect(Collectors.toCollection(ArrayList::new));
        // Drop any earlier transfer stop that has not been served; a new one is added below if needed.
        List<TripStop> staleTransfers = open.stream().filter(s -> s.getKind() == TripStop.Kind.TRANSFER).toList();
        open.removeAll(staleTransfers);
        staleTransfers.forEach(s -> s.finish(StopStatus.SKIPPED, clock.now()));

        if (transferSite != null) {
            open.forEach(TripStop::resetArrival);
            renumberForInsert(trip, open);
            TripStop transfer = new TripStop(TripStop.Kind.TRANSFER, "Cargo transfer",
                "Breakdown site of " + trip.getCode(), transferSite, TRANSFER_SERVICE_MIN);
            trip.addStop(transfer);
            int firstOpenSeq = open.isEmpty() ? transfer.getSeq() : open.get(0).getSeq() - 1;
            transfer.setSeq(firstOpenSeq);
            trips.flush();
            open.add(0, transfer);
            trip.getStops().sort(Comparator.comparingInt(TripStop::getSeq));
        }
        if (open.isEmpty()) {
            return;
        }
        List<GeoPoint> waypoints = new ArrayList<>();
        waypoints.add(origin);
        open.forEach(s -> waypoints.add(s.location()));
        RoutePlan plan = routing.route(waypoints);

        RouteVersion current = routes.findByTripIdAndActiveTrue(trip.getId()).orElse(null);
        int nextNo = 1;
        if (current != null) {
            current.deactivate();
            nextNo = current.getVersionNo() + 1;
            routes.flush();
        }
        RouteVersion next = routes.save(new RouteVersion(trip, nextNo, plan.encodedGeometry(), plan.distanceM(), plan.durationS(),
            plan.legDistancesM(), plan.legDurationsS(), plan.estimated(), reason, CurrentUser.username(), clock.now()));
        applySchedule(trip, next, clock.now(), open);
        audit.record("ROUTE_CHANGED", "TRIP", trip.getId(), trip.getCode() + " route v" + nextNo + ": " + reason,
            current == null ? null : AuditFields.of("version", current.getVersionNo(), "distanceM", current.getDistanceM()),
            AuditFields.of("version", nextNo, "distanceM", plan.distanceM(), "estimated", plan.estimated()));
        events.publishEvent(new TripEvents.RouteActivated(trip.getId()));
    }

    /** Shifts open stops up by one so a transfer stop can take the first open position. */
    private void renumberForInsert(Trip trip, List<TripStop> open) {
        if (open.isEmpty()) {
            return;
        }
        // Two passes avoid colliding with the (trip_id, seq) unique constraint mid-update.
        for (TripStop s : open) {
            s.setSeq(s.getSeq() + 10_000);
        }
        trips.flush();
        for (TripStop s : open) {
            s.setSeq(s.getSeq() - 10_000 + 1);
        }
        trips.flush();
    }

    @Transactional
    public void cancel(long tripId, String reason) {
        Trip trip = lock(tripId);
        Instant now = clock.now();
        TripStatus old = trip.getStatus();
        trip.cancel(now);
        for (TripStop s : trip.getStops()) {
            if (!s.getStatus().isDone()) {
                s.finish(StopStatus.SKIPPED, now);
            }
        }
        for (Shipment s : shipments.findByTripIdOrderByIdAsc(tripId)) {
            if (s.getStatus() == ShipmentStatus.PLANNED || s.getStatus() == ShipmentStatus.OUT_FOR_DELIVERY) {
                ShipmentStatus prev = s.getStatus();
                if (prev == ShipmentStatus.OUT_FOR_DELIVERY) {
                    s.transition(ShipmentStatus.PLANNED);
                }
                s.transition(ShipmentStatus.CREATED);
                s.unassign();
                history.record(StatusHistoryService.SHIPMENT, s.getId(), prev, ShipmentStatus.CREATED, "Trip " + trip.getCode() + " cancelled");
            }
        }
        release(trip.getVehicle(), trip.getDriver());
        history.record(StatusHistoryService.TRIP, tripId, old, TripStatus.CANCELLED, reason);
        audit.record("TRIP_CANCELLED", "TRIP", tripId, trip.getCode() + " cancelled" + (reason == null || reason.isBlank() ? "" : ": " + reason));
        events.publishEvent(new TripEvents.TripEnded(tripId));
        events.publishEvent(new TripEvents.TripChanged(tripId));
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Trip get(long tripId) {
        return trips.findWithResourcesById(tripId).orElseThrow(() -> new NotFoundException("Trip", tripId));
    }

    @Transactional(readOnly = true)
    public List<Trip> live() {
        return trips.findByStatusInOrderByPlannedStartAsc(LIVE);
    }

    private Trip lock(long tripId) {
        return trips.lockById(tripId).orElseThrow(() -> new NotFoundException("Trip", tripId));
    }

    private static void requireInProgress(Trip trip) {
        if (trip.getStatus() != TripStatus.IN_PROGRESS) {
            throw new BusinessRuleException(trip.getCode() + " is " + trip.getStatus().label().toLowerCase());
        }
    }

    private static TripStop stopOf(Trip trip, long stopId) {
        return trip.getStops().stream().filter(s -> s.getId() == stopId).findFirst()
            .orElseThrow(() -> new NotFoundException("Stop", stopId));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
