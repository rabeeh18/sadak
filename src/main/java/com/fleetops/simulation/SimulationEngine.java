package com.fleetops.simulation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fleetops.common.geo.GeoPoint;
import com.fleetops.common.geo.RouteLine;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.time.StartupGate;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.tracking.FleetBroadcaster;
import com.fleetops.tracking.LiveFleet;
import com.fleetops.tracking.LiveTrip;
import com.fleetops.tracking.LocationIngestionService;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripRepository;
import com.fleetops.trip.TripService;
import com.fleetops.trip.TripStatus;
import com.fleetops.trip.TripStop;

/**
 * Moves simulated vehicles along their cached routes and feeds their positions into the same
 * ingestion pipeline a real GPS device would use. Vehicles never move randomly: each follows
 * its active route at the planned leg speed, adjusted by any scenario the dispatcher applied.
 */
@Component
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);
    private static final String[] RECIPIENTS = {
        "Harpreet Kaur", "Rajinder Singh", "Sunita Devi", "Amit Sharma", "Kuldeep Gill", "Navneet Sandhu",
        "Simran Brar", "Gurdeep Dhillon", "Pooja Arora", "Jaspal Sidhu", "Manjit Grewal", "Ravi Kumar"};

    /** What the dispatcher has done to a simulated trip. */
    public enum Scenario { NORMAL, SLOW, BREAKDOWN, DETOUR, SILENT }

    private static final class SimState {
        int routeVersion;
        double distance;
        double speedFactor = 1;
        boolean silent;
        double detourStart = -1;
        double detourEnd = -1;
        double detourOffset;
        int extraDwellMin;
        double wobblePhase = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        Scenario scenario = Scenario.NORMAL;
    }

    private final LiveFleet live;
    private final LocationIngestionService ingestion;
    private final FleetBroadcaster broadcaster;
    private final TripService tripService;
    private final TripRepository trips;
    private final OperationalClock clock;
    private final StartupGate gate;
    private final Map<Long, SimState> states = new ConcurrentHashMap<>();
    private Instant lastTick;
    private int tickCount;

    public SimulationEngine(LiveFleet live, LocationIngestionService ingestion, FleetBroadcaster broadcaster,
                            TripService tripService, TripRepository trips, OperationalClock clock, StartupGate gate) {
        this.live = live;
        this.ingestion = ingestion;
        this.broadcaster = broadcaster;
        this.tripService = tripService;
        this.trips = trips;
        this.clock = clock;
        this.gate = gate;
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        if (!gate.isOpen()) {
            return;
        }
        Instant now = clock.now();
        double dt = lastTick == null ? 0 : Math.min(180, Duration.between(lastTick, now).toMillis() / 1000.0);
        lastTick = now;
        if (tickCount++ % 5 == 0) {
            startDueTrips(now);
        }
        for (LiveTrip lt : live.all()) {
            if (!lt.simulated) {
                continue;
            }
            try {
                step(lt, now, dt);
            } catch (RuntimeException e) {
                log.warn("Simulation step failed for {}: {}", lt.tripCode, e.getMessage());
            }
        }
        states.keySet().removeIf(tripId -> live.find(tripId).isEmpty());
        broadcaster.publishPositions();
    }

    /** Simulated trips depart on their own at their planned start time. */
    private void startDueTrips(Instant now) {
        List<Trip> due = trips.findByStatusInOrderByPlannedStartAsc(List.of(TripStatus.PLANNED)).stream()
            .filter(t -> !t.getPlannedStart().isAfter(now) && t.getVehicle().isSimulated() && t.isAutoCompleteStops())
            .toList();
        for (Trip t : due) {
            try {
                tripService.start(t.getId());
            } catch (RuntimeException e) {
                log.debug("Could not auto-start {}: {}", t.getCode(), e.getMessage());
            }
        }
    }

    private void step(LiveTrip lt, Instant now, double dt) {
        SimState st = states.computeIfAbsent(lt.tripId, id -> new SimState());
        LiveTrip.OpenStop dwellingAt;
        LiveTrip.OpenStop next;
        RouteLine route = lt.route;
        synchronized (lt) {
            if (st.routeVersion != lt.routeVersionNo) {
                st.routeVersion = lt.routeVersionNo;
                st.distance = lt.progress();
                st.detourStart = -1;
            }
            dwellingAt = lt.currentStopAt();
            next = lt.nextStop();
        }

        double speedMps = 0;
        if (lt.brokenDown()) {
            st.scenario = Scenario.BREAKDOWN;
        } else if (dwellingAt != null) {
            Instant due = dwellingAt.arrivedAt.plus(Duration.ofMinutes(dwellingAt.serviceMin + st.extraDwellMin));
            if (!now.isBefore(due)) {
                finishStop(lt, dwellingAt);
                st.extraDwellMin = 0;
            }
        } else if (next != null && dt > 0) {
            st.wobblePhase += dt / 90.0;
            double wobble = 1 + 0.08 * Math.sin(st.wobblePhase);
            speedMps = lt.plannedSpeedAt(st.distance) * st.speedFactor * wobble;
            st.distance = Math.min(next.distanceAlong, st.distance + speedMps * dt);
        }

        RouteLine.Located located = route.locate(st.distance);
        GeoPoint position = located.point();
        if (st.detourStart >= 0 && st.distance >= st.detourStart && st.distance <= st.detourEnd) {
            double t = (st.distance - st.detourStart) / (st.detourEnd - st.detourStart);
            position = position.offset(Math.sin(Math.PI * t) * st.detourOffset, located.bearing() + 90);
        } else if (st.detourStart >= 0 && st.distance > st.detourEnd) {
            st.detourStart = -1;
            if (st.scenario == Scenario.DETOUR) {
                st.scenario = Scenario.NORMAL;
            }
        }
        if (!st.silent) {
            ingestion.ingest(lt.vehicleId, position, speedMps * 3.6, now);
        }
        // Road snapping can leave a stop just outside the geofence; the simulated driver reports arrival.
        if (next != null && dwellingAt == null && !lt.brokenDown() && st.distance >= next.distanceAlong - 1
                && next.status == com.fleetops.trip.StopStatus.PENDING) {
            tripService.arrive(lt.tripId, next.stopId);
        }
    }

    private void finishStop(LiveTrip lt, LiveTrip.OpenStop stop) {
        if (stop.kind == TripStop.Kind.TRANSFER) {
            tripService.completeTransfer(lt.tripId, stop.stopId);
        } else if (lt.autoCompleteStops) {
            String recipient = RECIPIENTS[(int) (Math.abs(stop.stopId * 31) % RECIPIENTS.length)];
            tripService.completeStop(lt.tripId, stop.stopId, recipient, "Simulated delivery", stop.location);
        }
        // Otherwise the driver completes the stop from the driver app.
    }

    // ------------------------------------------------------------------ dispatcher scenario controls

    public void apply(long tripId, Scenario scenario) {
        LiveTrip lt = live.find(tripId).orElseThrow(() -> new NotFoundException("Live trip", tripId));
        if (!lt.simulated) {
            throw new BusinessRuleException(lt.tripCode + " uses a real location feed and cannot be simulated");
        }
        SimState st = states.computeIfAbsent(tripId, id -> new SimState());
        switch (scenario) {
            case NORMAL -> {
                st.speedFactor = 1;
                st.silent = false;
                st.detourStart = -1;
            }
            case SLOW -> st.speedFactor = 0.45;
            case SILENT -> st.silent = true;
            case DETOUR -> {
                st.detourStart = st.distance + 300;
                st.detourEnd = Math.min(lt.route.length() - 200, st.distance + 7000);
                st.detourOffset = 950;
                if (st.detourEnd <= st.detourStart) {
                    throw new BusinessRuleException(lt.tripCode + " is too close to the end of its route for a detour");
                }
            }
            case BREAKDOWN -> tripService.reportBreakdown(tripId, "Engine failure reported by driver (simulated)");
        }
        st.scenario = scenario;
    }

    public void extendStop(long tripId, int minutes) {
        LiveTrip lt = live.find(tripId).orElseThrow(() -> new NotFoundException("Live trip", tripId));
        SimState st = states.computeIfAbsent(lt.tripId, id -> new SimState());
        st.extraDwellMin += Math.max(1, Math.min(120, minutes));
    }

    public Scenario scenarioOf(long tripId) {
        SimState st = states.get(tripId);
        return st == null ? Scenario.NORMAL : st.scenario;
    }

    public int extraDwellOf(long tripId) {
        SimState st = states.get(tripId);
        return st == null ? 0 : st.extraDwellMin;
    }
}
