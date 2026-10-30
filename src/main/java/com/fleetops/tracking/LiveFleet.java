package com.fleetops.tracking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fleetops.common.geo.GeoPoint;
import com.fleetops.common.geo.RouteLine;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.fleet.Vehicle;
import com.fleetops.fleet.VehicleStatus;
import com.fleetops.trip.RouteVersion;
import com.fleetops.trip.RouteVersionRepository;
import com.fleetops.trip.StopStatus;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripEvents;
import com.fleetops.trip.TripRepository;
import com.fleetops.trip.TripStatus;
import com.fleetops.trip.TripStop;

/**
 * Registry of in-progress trips held in memory for tracking, simulation and ETAs.
 * It is rebuilt from the database on start-up and kept in step by trip events.
 */
@Component
public class LiveFleet {

    private static final Logger log = LoggerFactory.getLogger(LiveFleet.class);

    private final TripRepository trips;
    private final RouteVersionRepository routes;
    private final OperationalClock clock;
    private final Map<Long, LiveTrip> byTrip = new ConcurrentHashMap<>();
    private final Map<Long, Long> tripByVehicle = new ConcurrentHashMap<>();

    public LiveFleet(TripRepository trips, RouteVersionRepository routes, OperationalClock clock) {
        this.trips = trips;
        this.routes = routes;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    @Transactional(readOnly = true)
    public void loadAll() {
        for (Trip t : trips.findByStatusInOrderByPlannedStartAsc(List.of(TripStatus.IN_PROGRESS))) {
            load(t);
        }
        log.info("Live fleet loaded with {} in-progress trips", byTrip.size());
    }

    @TransactionalEventListener
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onRouteActivated(TripEvents.RouteActivated event) {
        trips.findWithResourcesById(event.tripId()).ifPresent(this::load);
    }

    @TransactionalEventListener
    public void onTripEnded(TripEvents.TripEnded event) {
        LiveTrip removed = byTrip.remove(event.tripId());
        if (removed != null) {
            tripByVehicle.remove(removed.vehicleId, removed.tripId);
        }
    }

    @TransactionalEventListener
    public void onArrived(TripEvents.StopArrived event) {
        find(event.tripId()).ifPresent(lt -> {
            synchronized (lt) {
                lt.markArrived(event.stopId(), clock.now());
            }
        });
    }

    @TransactionalEventListener
    public void onCompleted(TripEvents.StopCompleted event) {
        find(event.tripId()).ifPresent(lt -> {
            synchronized (lt) {
                lt.markDone(event.stopId(), clock.now());
            }
        });
    }

    @TransactionalEventListener
    public void onFailed(TripEvents.StopFailed event) {
        find(event.tripId()).ifPresent(lt -> {
            synchronized (lt) {
                lt.markDone(event.stopId(), clock.now());
            }
        });
    }

    @TransactionalEventListener
    public void onBreakdown(TripEvents.VehicleBrokeDown event) {
        find(event.tripId()).ifPresent(lt -> {
            synchronized (lt) {
                lt.setBrokenDown(true);
            }
        });
    }

    /** Builds the live picture of a trip from its active route version and open stops. */
    private void load(Trip trip) {
        if (trip.getStatus() != TripStatus.IN_PROGRESS) {
            return;
        }
        Optional<RouteVersion> active = routes.findByTripIdAndActiveTrue(trip.getId());
        if (active.isEmpty()) {
            log.warn("Trip {} has no active route; not tracked", trip.getCode());
            return;
        }
        RouteVersion rv = active.get();
        RouteLine line = RouteLine.fromEncoded(rv.getGeometry());
        List<Integer> legDistances = rv.legDistances();
        List<Integer> legDurations = rv.legDurations();

        Vehicle vehicle = trip.getVehicle();
        LiveTrip previous = byTrip.get(trip.getId());
        List<LiveTrip.OpenStop> open = new ArrayList<>();
        LiveTrip probe = new LiveTrip(trip.getId(), trip.getCode(), vehicle.getId(), vehicle.getRegistration(),
            trip.getDriver().getFullName(), rv.getVersionNo(), line, legDistances, legDurations, List.of(),
            vehicle.isSimulated(), trip.isAutoCompleteStops(), trip.getDelayAdjustMin(), false);
        for (TripStop s : trip.getStops()) {
            if (s.getStatus().isDone() || s.getRouteVersionNo() != rv.getVersionNo()) {
                continue;
            }
            open.add(new LiveTrip.OpenStop(s.getId(), s.getSeq(), s.getName(), s.getKind(), s.location(),
                probe.legEndDistance(s.getLegIndex()), s.getServiceMin(), s.getPlannedArrival(), s.getStatus(),
                s.getStatus() == StopStatus.ARRIVED ? s.getActualArrival() : null));
        }
        LiveTrip lt = new LiveTrip(trip.getId(), trip.getCode(), vehicle.getId(), vehicle.getRegistration(),
            trip.getDriver().getFullName(), rv.getVersionNo(), line, legDistances, legDurations, open,
            vehicle.isSimulated(), trip.isAutoCompleteStops(), trip.getDelayAdjustMin(),
            vehicle.getStatus() == VehicleStatus.BROKEN_DOWN);

        // Where is the vehicle now? Same version: keep live progress. New version: starts at the route origin.
        if (previous != null && previous.routeVersionNo == rv.getVersionNo() && previous.position() != null) {
            synchronized (previous) {
                lt.seedPosition(previous.position(), previous.progress(), previous.lastFixAt());
            }
        } else {
            GeoPoint last = vehicle.lastPosition();
            var projection = line.project(last, 0);
            double progress = rv.getVersionNo() == 1 && trip.getActualStart() != null
                && vehicle.getLastSeenAt() != null && vehicle.getLastSeenAt().isAfter(trip.getActualStart())
                ? projection.distanceAlong() : 0;
            GeoPoint seed = progress == 0 ? line.pointAt(0) : last;
            lt.seedPosition(seed, progress, vehicle.getLastSeenAt() != null ? vehicle.getLastSeenAt() : clock.now());
        }
        if (previous != null && previous.vehicleId != vehicle.getId()) {
            tripByVehicle.remove(previous.vehicleId, trip.getId());
        }
        byTrip.put(trip.getId(), lt);
        tripByVehicle.put(vehicle.getId(), trip.getId());
    }

    /** Re-reads a trip's dispatcher-adjusted fields (delay adjustment) without losing live state. */
    @Transactional(readOnly = true)
    public void refresh(long tripId) {
        trips.findWithResourcesById(tripId).ifPresent(this::load);
    }

    public Optional<LiveTrip> find(long tripId) {
        return Optional.ofNullable(byTrip.get(tripId));
    }

    public Optional<LiveTrip> forVehicle(long vehicleId) {
        Long tripId = tripByVehicle.get(vehicleId);
        return tripId == null ? Optional.empty() : find(tripId);
    }

    public Collection<LiveTrip> all() {
        return byTrip.values();
    }
}
