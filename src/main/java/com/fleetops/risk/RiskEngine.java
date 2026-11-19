package com.fleetops.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fleetops.settings.SettingsService;
import com.fleetops.settings.SettingsService.RuleSnapshot;
import com.fleetops.shipment.Shipment;
import com.fleetops.tracking.LiveTrip;
import com.fleetops.trip.RiskBand;

/**
 * Transparent, rule-based problem detection. Every rule is either a heads-up (Watch) or a problem
 * that needs a person (Needs action); the trip's light is the most serious one that holds. Each
 * rule also adds points, shown as the explanation underneath, which admins can tune.
 */
@Component
public class RiskEngine {

    /** Rules that are only a heads-up. Every other rule needs action. */
    public static final Set<String> WATCH_RULES = Set.of("DEADLINE_PRESSURE", "PRIOR_DELAYS");

    private final SettingsService settings;

    public RiskEngine(SettingsService settings) {
        this.settings = settings;
    }

    public RiskAssessment assess(LiveTrip lt, EtaCalculator.TripEta eta, List<Shipment> openShipments, Instant now) {
        List<RiskAssessment.Contribution> out = new ArrayList<>();
        int worstLate = 0;
        String worstTracking = null;
        int close = 0;
        int late = 0;
        int marginMin = settings.getInt("sla.at-risk-min");
        int lateFrom = settings.getInt("risk.late-action-min");
        for (Shipment s : openShipments) {
            if (s.getStop() == null) {
                continue;
            }
            Instant stopEta = eta.byStop().get(s.getStop().getId());
            if (stopEta == null) {
                continue;
            }
            long minutesLate = Duration.between(s.getDeadline(), stopEta).toMinutes();
            if (minutesLate >= lateFrom) {
                late++;
                if (minutesLate > worstLate) {
                    worstLate = (int) minutesLate;
                    worstTracking = s.getTrackingNo();
                }
            } else if (minutesLate > -marginMin) {
                // A little early, on time, or only a few minutes over: a heads-up, not yet a problem.
                close++;
            }
        }

        RuleSnapshot projected = settings.rule("PROJECTED_LATE");
        if (projected.enabled() && worstLate > 0) {
            double rate = projected.threshold() == null ? 1 : projected.threshold();
            int points = (int) Math.min(projected.points(), Math.round(rate * worstLate));
            add(out, projected, Math.max(1, points), worstTracking + " will arrive " + worstLate + " min after its promised time"
                + (late > 1 ? " (" + (late - 1) + " more parcel" + (late == 2 ? "" : "s") + " late too)" : ""));
        }
        RuleSnapshot pressure = settings.rule("DEADLINE_PRESSURE");
        if (pressure.enabled() && close > 0) {
            add(out, pressure, pressure.points(), close + (close == 1 ? " parcel arrives" : " parcels arrive")
                + " within " + marginMin + " min of the promised time");
        }
        synchronized (lt) {
            RuleSnapshot breakdown = settings.rule("BREAKDOWN");
            if (breakdown.enabled() && lt.brokenDown()) {
                add(out, breakdown, breakdown.points(), "The driver reported that " + lt.registration + " has broken down");
            }
            long sinceFix = lt.lastFixAt() == null ? Long.MAX_VALUE : Duration.between(lt.lastFixAt(), now).toSeconds();
            RuleSnapshot stale = settings.rule("LOCATION_STALE");
            if (stale.enabled() && sinceFix > stale.threshold() * 60) {
                add(out, stale, stale.points(), sinceFix == Long.MAX_VALUE ? "No position received yet"
                    : "No position for " + sinceFix / 60 + " min");
            }
            LiveTrip.OpenStop atStop = lt.currentStopAt();
            RuleSnapshot unexpected = settings.rule("UNEXPECTED_STOP");
            if (unexpected.enabled() && atStop == null && !lt.brokenDown() && lt.lastMovedAt() != null
                    && sinceFix <= stale.threshold() * 60) {
                long still = Duration.between(lt.lastMovedAt(), now).toMinutes();
                if (still > unexpected.threshold()) {
                    add(out, unexpected, unexpected.points(), "Not moving for " + still + " min, away from any delivery stop");
                }
            }
            RuleSnapshot longStop = settings.rule("LONG_STOP");
            if (longStop.enabled() && atStop != null && atStop.arrivedAt != null) {
                long dwell = Duration.between(atStop.arrivedAt, now).toMinutes();
                if (dwell > atStop.serviceMin + longStop.threshold()) {
                    add(out, longStop, longStop.points(), dwell + " min at " + atStop.name + " so far, planned " + atStop.serviceMin + " min");
                }
            }
            RuleSnapshot deviation = settings.rule("ROUTE_DEVIATION");
            if (deviation.enabled() && lt.offRouteMeters() > deviation.threshold()) {
                add(out, deviation, deviation.points(), String.format("%.1f km away from its planned road", lt.offRouteMeters() / 1000));
            }
        }
        RuleSnapshot prior = settings.rule("PRIOR_DELAYS");
        long delayedBefore = openShipments.stream().filter(s -> s.isWasDelayed() || s.getAttempts() > 0).count();
        if (prior.enabled() && delayedBefore > 0) {
            add(out, prior, prior.points(), delayedBefore + (delayedBefore == 1 ? " parcel on board was" : " parcels on board were")
                + " late or re-attempted before");
        }

        // Problems that need action first, then heads-ups; within each, the heaviest first.
        out.sort((a, b) -> a.watch() != b.watch() ? (a.watch() ? 1 : -1) : Integer.compare(b.points(), a.points()));
        int score = Math.min(100, out.stream().mapToInt(RiskAssessment.Contribution::points).sum());
        return new RiskAssessment(score, light(out), List.copyOf(out), worstLate, close, late);
    }

    /** On track, Watch, or Needs action: the most serious problem that holds, whatever the points add up to. */
    static RiskBand light(List<RiskAssessment.Contribution> found) {
        if (found.stream().anyMatch(c -> !c.watch())) {
            return RiskBand.HIGH;
        }
        return found.isEmpty() ? RiskBand.LOW : RiskBand.MEDIUM;
    }

    private static void add(List<RiskAssessment.Contribution> out, RuleSnapshot rule, int points, String detail) {
        out.add(new RiskAssessment.Contribution(rule.code(), rule.label(), points, detail, WATCH_RULES.contains(rule.code())));
    }
}
