package com.fleetops.tracking;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fleetops.common.geo.GeoPoint;
import com.fleetops.common.geo.RouteLine;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.settings.SettingsService;
import com.fleetops.trip.StopStatus;
import com.fleetops.trip.TripService;

/**
 * The one pipeline every location fix goes through, whether it comes from the simulator,
 * a driver's phone or a GPS device: update the live picture, detect stop arrival by
 * geofence, store throttled history, and queue the fix for broadcast.
 */
@Service
public class LocationIngestionService {

    private static final Logger log = LoggerFactory.getLogger(LocationIngestionService.class);

    /** Latest fix per vehicle, including vehicles not on a trip. */
    public record Fix(long vehicleId, GeoPoint position, double speedKph, double heading, Instant at) {
    }

    private final LiveFleet live;
    private final TripService tripService;
    private final SettingsService settings;
    private final OperationalClock clock;
    private final LocationWriter writer;
    private final Map<Long, Fix> latest = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastStored = new ConcurrentHashMap<>();

    public LocationIngestionService(LiveFleet live, TripService tripService, SettingsService settings,
                                    OperationalClock clock, LocationWriter writer) {
        this.live = live;
        this.tripService = tripService;
        this.settings = settings;
        this.clock = clock;
        this.writer = writer;
        writer.bind(this);
    }

    public void ingest(long vehicleId, GeoPoint position, Double speedKph, Instant at) {
        Instant when = at != null ? at : clock.now();
        LiveTrip lt = live.forVehicle(vehicleId).orElse(null);
        double heading = 0;
        Long arrivalStopId = null;
        if (lt != null) {
            synchronized (lt) {
                GeoPoint previous = lt.position();
                heading = previous != null && previous.distanceTo(position) > 3 ? previous.bearingTo(position) : lt.heading();
                RouteLine.Projection p = lt.route.project(position, lt.progress());
                double speed = speedKph != null ? speedKph : estimateSpeed(lt, position, when);
                lt.applyFix(position, p.distanceAlong(), p.offsetMeters(), speed, heading, when);
                LiveTrip.OpenStop next = lt.nextStop();
                double radius = settings.getInt("tracking.geofence-m");
                if (next != null && next.status == StopStatus.PENDING && position.distanceTo(next.location) <= radius) {
                    arrivalStopId = next.stopId;
                }
            }
        }
        latest.put(vehicleId, new Fix(vehicleId, position, lt != null ? lt.speedKph() : (speedKph == null ? 0 : speedKph), heading, when));

        int every = settings.getInt("tracking.persist-every-s");
        Instant stored = lastStored.get(vehicleId);
        if (stored == null || Duration.between(stored, when).toSeconds() >= every) {
            lastStored.put(vehicleId, when);
            writer.enqueue(new VehicleLocation(vehicleId, lt != null ? lt.tripId : null, when,
                position.lat(), position.lng(), (float) (lt != null ? lt.speedKph() : (speedKph == null ? 0 : speedKph))));
        }

        if (arrivalStopId != null) {
            try {
                tripService.arrive(lt.tripId, arrivalStopId);
            } catch (RuntimeException e) {
                log.debug("Arrival for trip {} stop {} not recorded: {}", lt.tripId, arrivalStopId, e.getMessage());
            }
        }
    }

    private static double estimateSpeed(LiveTrip lt, GeoPoint position, Instant at) {
        if (lt.position() == null || lt.lastFixAt() == null) {
            return 0;
        }
        double seconds = Duration.between(lt.lastFixAt(), at).toMillis() / 1000.0;
        if (seconds <= 0) {
            return lt.speedKph();
        }
        return lt.position().distanceTo(position) / seconds * 3.6;
    }

    public Map<Long, Fix> latestFixes() {
        return latest;
    }

    public Fix latest(long vehicleId) {
        return latest.get(vehicleId);
    }
}
