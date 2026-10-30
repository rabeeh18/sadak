package com.fleetops.tracking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.fleetops.common.geo.GeoPoint;
import com.fleetops.common.geo.RouteLine;
import com.fleetops.trip.StopStatus;
import com.fleetops.trip.TripStop;

/**
 * In-memory operational picture of one in-progress trip: where the vehicle is along its active
 * route, which stops remain, and how fast it has really been moving. Updated by location
 * ingestion; read by the simulator, ETA engine and broadcaster. Guard access with the instance lock.
 */
public final class LiveTrip {

    /** A stop still to be served on the active route, with its position along that route. */
    public static final class OpenStop {
        public final long stopId;
        public final int seq;
        public final String name;
        public final TripStop.Kind kind;
        public final GeoPoint location;
        public final double distanceAlong;
        public final int serviceMin;
        public final Instant plannedArrival;
        public StopStatus status;
        public Instant arrivedAt;

        OpenStop(long stopId, int seq, String name, TripStop.Kind kind, GeoPoint location, double distanceAlong,
                 int serviceMin, Instant plannedArrival, StopStatus status, Instant arrivedAt) {
            this.stopId = stopId;
            this.seq = seq;
            this.name = name;
            this.kind = kind;
            this.location = location;
            this.distanceAlong = distanceAlong;
            this.serviceMin = serviceMin;
            this.plannedArrival = plannedArrival;
            this.status = status;
            this.arrivedAt = arrivedAt;
        }
    }

    private record PaceSample(Instant at, double distanceAlong) {
    }

    public final long tripId;
    public final String tripCode;
    public final long vehicleId;
    public final String registration;
    public final String driverName;
    public final int routeVersionNo;
    public final RouteLine route;
    public final boolean simulated;
    public final boolean autoCompleteStops;
    public final int delayAdjustMin;

    /** Cumulative distance at the end of each leg, and planned seconds per leg. */
    private final double[] legEnd;
    private final double[] legSeconds;

    private final List<OpenStop> openStops;
    private final Deque<PaceSample> pace = new ArrayDeque<>();

    private GeoPoint position;
    private double progress;
    private double offRouteMeters;
    private double speedKph;
    private double heading;
    private Instant lastFixAt;
    private Instant lastMovedAt;
    private boolean brokenDown;

    LiveTrip(long tripId, String tripCode, long vehicleId, String registration, String driverName, int routeVersionNo,
             RouteLine route, List<Integer> legDistances, List<Integer> legDurations, List<OpenStop> openStops,
             boolean simulated, boolean autoCompleteStops, int delayAdjustMin, boolean brokenDown) {
        this.tripId = tripId;
        this.tripCode = tripCode;
        this.vehicleId = vehicleId;
        this.registration = registration;
        this.driverName = driverName;
        this.routeVersionNo = routeVersionNo;
        this.route = route;
        this.openStops = new ArrayList<>(openStops);
        this.simulated = simulated;
        this.autoCompleteStops = autoCompleteStops;
        this.delayAdjustMin = delayAdjustMin;
        this.brokenDown = brokenDown;
        // OSRM leg distances and the decoded geometry length differ slightly; scale legs onto the geometry.
        double legTotal = legDistances.stream().mapToDouble(Integer::doubleValue).sum();
        double scale = legTotal > 0 ? route.length() / legTotal : 1;
        this.legEnd = new double[legDistances.size()];
        this.legSeconds = new double[legDurations.size()];
        double acc = 0;
        for (int i = 0; i < legDistances.size(); i++) {
            acc += legDistances.get(i) * scale;
            legEnd[i] = acc;
            legSeconds[i] = legDurations.get(i);
        }
    }

    /** Distance along the route at the end of a leg (i.e. at the stop that ends it). */
    public double legEndDistance(int legIndex) {
        return legEnd[Math.max(0, Math.min(legEnd.length - 1, legIndex))];
    }

    /** Planned driving seconds from the route start to a distance along it. */
    public double plannedSecondsAt(double distance) {
        double start = 0;
        double seconds = 0;
        for (int i = 0; i < legEnd.length; i++) {
            double end = legEnd[i];
            if (distance <= end || i == legEnd.length - 1) {
                double len = end - start;
                double t = len <= 0 ? 1 : Math.max(0, Math.min(1, (distance - start) / len));
                return seconds + legSeconds[i] * t;
            }
            seconds += legSeconds[i];
            start = end;
        }
        return seconds;
    }

    /** Planned speed (m/s) at a distance along the route; used by the simulator. */
    public double plannedSpeedAt(double distance) {
        double start = 0;
        for (int i = 0; i < legEnd.length; i++) {
            if (distance <= legEnd[i] || i == legEnd.length - 1) {
                double len = legEnd[i] - start;
                return legSeconds[i] > 0 ? Math.max(3, len / legSeconds[i]) : 11;
            }
            start = legEnd[i];
        }
        return 11;
    }

    // ------------------------------------------------------------------ updates (call while holding the lock)

    void applyFix(GeoPoint p, double projectedProgress, double offRoute, double speed, double bearing, Instant at) {
        boolean moved = position == null || position.distanceTo(p) > 25;
        if (moved || lastMovedAt == null) {
            lastMovedAt = at;
        }
        position = p;
        // Never let progress jump backwards by more than a little; GPS noise near stops does that.
        progress = projectedProgress < progress - 200 ? progress : Math.max(progress, projectedProgress);
        offRouteMeters = offRoute;
        speedKph = speed;
        heading = bearing;
        lastFixAt = at;
        if (currentStopAt() == null && offRoute < 300) {
            pace.addLast(new PaceSample(at, progress));
            while (pace.size() > 2 && Duration.between(pace.peekFirst().at, at).toMinutes() > 20) {
                pace.removeFirst();
            }
        }
    }

    void markArrived(long stopId, Instant at) {
        for (OpenStop s : openStops) {
            if (s.stopId == stopId) {
                s.status = StopStatus.ARRIVED;
                s.arrivedAt = at;
            }
        }
        pace.clear();
        lastMovedAt = at;
    }

    /** Time spent serving a stop is not "standing still", so the stationary clock restarts on departure. */
    void markDone(long stopId, Instant at) {
        openStops.removeIf(s -> s.stopId == stopId);
        pace.clear();
        lastMovedAt = at;
    }

    void setBrokenDown(boolean brokenDown) {
        this.brokenDown = brokenDown;
    }

    /** Seeds progress when a trip is loaded mid-route (e.g. after a restart). */
    void seedPosition(GeoPoint p, double progress, Instant lastFix) {
        this.position = p;
        this.progress = progress;
        this.lastFixAt = lastFix;
        this.lastMovedAt = lastFix;
    }

    // ------------------------------------------------------------------ reads

    /**
     * Observed pace as a multiple of planned driving time (1.0 = on plan, 1.5 = 50% slower),
     * measured over the last stretch of driving. Returns 1 when there is too little evidence.
     */
    public double paceFactor() {
        if (pace.size() < 2) {
            return 1;
        }
        PaceSample first = pace.peekFirst();
        PaceSample last = pace.peekLast();
        double dd = last.distanceAlong - first.distanceAlong;
        double dt = Duration.between(first.at, last.at).toSeconds();
        if (dt < 240) {
            return 1;
        }
        if (dd < 50) {
            return 2.5; // effectively stationary while supposedly driving
        }
        double planned = plannedSecondsAt(last.distanceAlong) - plannedSecondsAt(first.distanceAlong);
        if (planned <= 0) {
            return 1;
        }
        return Math.max(0.85, Math.min(2.5, dt / planned));
    }

    public OpenStop currentStopAt() {
        return openStops.stream().filter(s -> s.status == StopStatus.ARRIVED).findFirst().orElse(null);
    }

    public OpenStop nextStop() {
        return openStops.isEmpty() ? null : openStops.get(0);
    }

    public List<OpenStop> openStops() {
        return List.copyOf(openStops);
    }

    public GeoPoint position() {
        return position;
    }

    public double progress() {
        return progress;
    }

    public double offRouteMeters() {
        return offRouteMeters;
    }

    public double speedKph() {
        return speedKph;
    }

    public double heading() {
        return heading;
    }

    public Instant lastFixAt() {
        return lastFixAt;
    }

    public Instant lastMovedAt() {
        return lastMovedAt;
    }

    public boolean brokenDown() {
        return brokenDown;
    }
}
