package com.fleetops.tracking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.settings.SettingsService;

/** Pushes live state to dispatcher screens over STOMP. Payloads are small, flat maps. */
@Component
public class FleetBroadcaster {

    public static final String POSITIONS = "/topic/fleet/positions";
    public static final String TRIPS = "/topic/fleet/trips";
    public static final String INCIDENTS = "/topic/fleet/incidents";

    private final SimpMessagingTemplate messaging;
    private final LocationIngestionService ingestion;
    private final LiveFleet live;
    private final OperationalClock clock;
    private final SettingsService settings;

    public FleetBroadcaster(SimpMessagingTemplate messaging, LocationIngestionService ingestion, LiveFleet live,
                            OperationalClock clock, SettingsService settings) {
        this.messaging = messaging;
        this.ingestion = ingestion;
        this.live = live;
        this.clock = clock;
        this.settings = settings;
    }

    /** One frame with the simulation clock and every known vehicle position. */
    public void publishPositions() {
        messaging.convertAndSend(POSITIONS, (Object) positionsFrame());
    }

    public Map<String, Object> positionsFrame() {
        Instant now = clock.now();
        long staleAfter = (long) (settings.rule("LOCATION_STALE").threshold() * 60);
        List<Map<String, Object>> vehicles = new ArrayList<>();
        for (LocationIngestionService.Fix fix : ingestion.latestFixes().values()) {
            LiveTrip lt = live.forVehicle(fix.vehicleId()).orElse(null);
            boolean stale = Duration.between(fix.at(), now).toSeconds() > staleAfter;
            vehicles.add(Map.of(
                "v", fix.vehicleId(),
                "trip", lt == null ? 0 : lt.tripId,
                "lat", round(fix.position().lat()),
                "lng", round(fix.position().lng()),
                "h", Math.round(fix.heading()),
                "s", Math.round(fix.speedKph()),
                "stale", stale,
                "age", Duration.between(fix.at(), now).toSeconds()));
        }
        return Map.of("t", now.toEpochMilli(), "speed", clock.speed(), "paused", clock.paused(), "vehicles", vehicles);
    }

    public void publishTrips(Object payload) {
        messaging.convertAndSend(TRIPS, payload);
    }

    public void publishIncident(Object payload) {
        messaging.convertAndSend(INCIDENTS, payload);
    }

    public void publishToUser(String username, String queue, Object payload) {
        messaging.convertAndSendToUser(username, queue, payload);
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
