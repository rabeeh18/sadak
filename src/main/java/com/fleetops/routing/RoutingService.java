package com.fleetops.routing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fleetops.common.geo.GeoPoint;
import com.fleetops.common.geo.Polyline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Road routing through the public OSRM server. Each route plan is fetched once and stored with
 * the trip, so the simulator and ETA engine never call out. Requests are throttled to one per
 * second (the public server's usage policy) and cached in memory; when OSRM cannot be reached,
 * a straight-line route at a configured average speed is returned and marked as estimated.
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);
    private static final int CACHE_LIMIT = 200;

    private final RoutingProperties props;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Map<String, RoutePlan> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RoutePlan> eldest) {
            return size() > CACHE_LIMIT;
        }
    };
    private long lastCallNanos;

    public RoutingService(RoutingProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
    }

    public RoutePlan route(List<GeoPoint> waypoints) {
        if (waypoints.size() < 2) {
            throw new IllegalArgumentException("A route needs at least two waypoints");
        }
        String key = waypoints.stream().map(p -> String.format("%.5f,%.5f", p.lng(), p.lat()))
            .collect(Collectors.joining(";"));
        synchronized (cache) {
            RoutePlan cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        RoutePlan plan;
        try {
            plan = fetchFromOsrm(key, waypoints.size() - 1);
        } catch (RuntimeException e) {
            log.warn("OSRM routing failed ({}); using straight-line estimate", e.getMessage());
            plan = straightLine(waypoints);
        }
        synchronized (cache) {
            if (!plan.estimated()) {
                cache.put(key, plan);
            }
        }
        return plan;
    }

    private RoutePlan fetchFromOsrm(String coordinates, int expectedLegs) {
        throttle();
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.osrmBaseUrl() + "/route/v1/driving/" + coordinates
                + "?overview=full&geometries=polyline&steps=false"))
            .timeout(props.timeout())
            .header("User-Agent", "fleet-ops-portfolio/1.0")
            .GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        JsonNode root = json.readTree(response.body());
        if (!"Ok".equals(root.path("code").asString())) {
            throw new IllegalStateException("OSRM returned " + root.path("code").asString());
        }
        JsonNode route = root.path("routes").get(0);
        List<Integer> legDistances = new ArrayList<>();
        List<Integer> legDurations = new ArrayList<>();
        for (JsonNode leg : route.path("legs")) {
            legDistances.add((int) Math.round(leg.path("distance").asDouble()));
            legDurations.add((int) Math.round(leg.path("duration").asDouble()));
        }
        if (legDistances.size() != expectedLegs) {
            throw new IllegalStateException("OSRM returned " + legDistances.size() + " legs, expected " + expectedLegs);
        }
        return new RoutePlan(route.path("geometry").asString(),
            (int) Math.round(route.path("distance").asDouble()),
            (int) Math.round(route.path("duration").asDouble()),
            legDistances, legDurations, false);
    }

    /** Fallback: straight segments, road factor applied to distance, constant average speed. */
    public RoutePlan straightLine(List<GeoPoint> waypoints) {
        List<Integer> legDistances = new ArrayList<>();
        List<Integer> legDurations = new ArrayList<>();
        List<GeoPoint> densified = new ArrayList<>();
        double speedMps = props.fallbackSpeedKph() / 3.6;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            GeoPoint a = waypoints.get(i);
            GeoPoint b = waypoints.get(i + 1);
            double d = a.distanceTo(b) * props.fallbackRoadFactor();
            legDistances.add((int) Math.round(d));
            legDurations.add((int) Math.round(d / speedMps));
            // Densify so vehicles move smoothly and projection stays accurate.
            int steps = Math.max(1, (int) (a.distanceTo(b) / 500));
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                densified.add(new GeoPoint(a.lat() + (b.lat() - a.lat()) * t, a.lng() + (b.lng() - a.lng()) * t));
            }
        }
        densified.add(waypoints.get(waypoints.size() - 1));
        int distance = legDistances.stream().mapToInt(Integer::intValue).sum();
        int duration = legDurations.stream().mapToInt(Integer::intValue).sum();
        return new RoutePlan(Polyline.encode(densified), distance, duration, legDistances, legDurations, true);
    }

    private synchronized void throttle() {
        long now = System.nanoTime();
        long waitNanos = 1_100_000_000L - (now - lastCallNanos);
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallNanos = System.nanoTime();
    }
}
