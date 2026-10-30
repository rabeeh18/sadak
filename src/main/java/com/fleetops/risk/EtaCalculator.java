package com.fleetops.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fleetops.tracking.LiveTrip;
import com.fleetops.trip.StopStatus;

/**
 * Dynamic ETA for every remaining stop of a trip:
 * <pre>
 *   ETA(stop k) = now
 *               + remaining time at the stop the vehicle is at (if any)
 *               + pace x planned drive time from here to stop k
 *               + planned service time of the stops before k
 *               + dispatcher adjustment
 * </pre>
 * Planned drive time comes from the cached route legs; pace is the observed ratio of actual to
 * planned driving time over the last stretch (1.0 = on plan).
 */
@Component
public class EtaCalculator {

    public record TripEta(Map<Long, Instant> byStop, double pace, boolean unknown) {
    }

    public TripEta compute(LiveTrip lt, int delayAdjustMin, Instant now) {
        Map<Long, Instant> etas = new LinkedHashMap<>();
        synchronized (lt) {
            if (lt.brokenDown()) {
                lt.openStops().forEach(s -> etas.put(s.stopId, null));
                return new TripEta(etas, 1, true);
            }
            double pace = lt.paceFactor();
            double here = lt.plannedSecondsAt(lt.progress());
            double serviceBefore = 0;
            double dwellRemaining = 0;
            LiveTrip.OpenStop current = lt.currentStopAt();
            if (current != null) {
                Instant due = current.arrivedAt.plus(Duration.ofMinutes(current.serviceMin));
                dwellRemaining = Math.max(0, Duration.between(now, due).toSeconds());
            }
            for (LiveTrip.OpenStop s : lt.openStops()) {
                if (s.status == StopStatus.ARRIVED) {
                    etas.put(s.stopId, s.arrivedAt);
                    continue;
                }
                double drive = Math.max(0, lt.plannedSecondsAt(s.distanceAlong) - here) * pace;
                long seconds = Math.round(dwellRemaining + drive + serviceBefore) + delayAdjustMin * 60L;
                etas.put(s.stopId, now.plusSeconds(Math.max(0, seconds)));
                serviceBefore += s.serviceMin * 60.0;
            }
            return new TripEta(etas, pace, false);
        }
    }
}
