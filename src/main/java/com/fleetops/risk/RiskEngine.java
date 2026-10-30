package com.fleetops.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fleetops.settings.SettingsService;
import com.fleetops.settings.SettingsService.RuleSnapshot;
import com.fleetops.shipment.Shipment;
import com.fleetops.tracking.LiveTrip;
import com.fleetops.trip.RiskBand;

/**
 * Transparent, rule-based delivery risk. Each enabled rule adds points when its condition holds;
 * the total maps to a band. This is an operational indicator, not a probability of failure.
 */
@Component
public class RiskEngine {

    private final SettingsService settings;

    public RiskEngine(SettingsService settings) {
        this.settings = settings;
    }

    public RiskAssessment assess(LiveTrip lt, EtaCalculator.TripEta eta, List<Shipment> openShipments, Instant now) {
        List<RiskAssessment.Contribution> out = new ArrayList<>();
        int worstLate = 0;
        String worstTracking = null;
        int atRisk = 0;
        int late = 0;
        int marginMin = settings.getInt("sla.at-risk-min");

        for (Shipment s : openShipments) {
            if (s.getStop() == null) {
                continue;
            }
            Instant stopEta = eta.byStop().get(s.getStop().getId());
            if (stopEta == null) {
                continue;
            }
            long minutesLate = Duration.between(s.getDeadline(), stopEta).toMinutes();
            if (minutesLate > 0) {
                late++;
                if (minutesLate > worstLate) {
                    worstLate = (int) minutesLate;
                    worstTracking = s.getTrackingNo();
                }
            } else if (-minutesLate <= marginMin) {
                atRisk++;
            }
        }

        RuleSnapshot projected = settings.rule("PROJECTED_LATE");
        if (projected.enabled() && worstLate > 0) {
            double rate = projected.threshold() == null ? 1 : projected.threshold();
            int points = (int) Math.min(projected.points(), Math.round(rate * worstLate));
            out.add(new RiskAssessment.Contribution(projected.code(), projected.label(), Math.max(1, points),
                worstTracking + " projected " + worstLate + " min late" + (late > 1 ? " (" + late + " shipments late)" : "")));
        }
        RuleSnapshot pressure = settings.rule("DEADLINE_PRESSURE");
        if (pressure.enabled() && atRisk > 0) {
            out.add(new RiskAssessment.Contribution(pressure.code(), pressure.label(), pressure.points(),
                atRisk + (atRisk == 1 ? " shipment" : " shipments") + " within " + marginMin + " min of deadline"));
        }

        synchronized (lt) {
            RuleSnapshot breakdown = settings.rule("BREAKDOWN");
            if (breakdown.enabled() && lt.brokenDown()) {
                out.add(new RiskAssessment.Contribution(breakdown.code(), breakdown.label(), breakdown.points(),
                    lt.registration + " reported broken down"));
            }
            long sinceFix = lt.lastFixAt() == null ? Long.MAX_VALUE : Duration.between(lt.lastFixAt(), now).toSeconds();
            RuleSnapshot stale = settings.rule("LOCATION_STALE");
            if (stale.enabled() && sinceFix > stale.threshold() * 60) {
                out.add(new RiskAssessment.Contribution(stale.code(), stale.label(), stale.points(),
                    "no location for " + (sinceFix == Long.MAX_VALUE ? "—" : sinceFix / 60 + " min")));
            }
            LiveTrip.OpenStop atStop = lt.currentStopAt();
            RuleSnapshot unexpected = settings.rule("UNEXPECTED_STOP");
            if (unexpected.enabled() && atStop == null && !lt.brokenDown() && lt.lastMovedAt() != null
                    && sinceFix <= stale.threshold() * 60) {
                long still = Duration.between(lt.lastMovedAt(), now).toMinutes();
                if (still > unexpected.threshold()) {
                    out.add(new RiskAssessment.Contribution(unexpected.code(), unexpected.label(), unexpected.points(),
                        "stationary for " + still + " min away from any stop"));
                }
            }
            RuleSnapshot longStop = settings.rule("LONG_STOP");
            if (longStop.enabled() && atStop != null && atStop.arrivedAt != null) {
                long dwell = Duration.between(atStop.arrivedAt, now).toMinutes();
                if (dwell > atStop.serviceMin + longStop.threshold()) {
                    out.add(new RiskAssessment.Contribution(longStop.code(), longStop.label(), longStop.points(),
                        dwell + " min at " + atStop.name + " (planned " + atStop.serviceMin + ")"));
                }
            }
            RuleSnapshot deviation = settings.rule("ROUTE_DEVIATION");
            if (deviation.enabled() && lt.offRouteMeters() > deviation.threshold()) {
                out.add(new RiskAssessment.Contribution(deviation.code(), deviation.label(), deviation.points(),
                    String.format("%.1f km from planned route", lt.offRouteMeters() / 1000)));
            }
        }

        RuleSnapshot prior = settings.rule("PRIOR_DELAYS");
        long delayedBefore = openShipments.stream().filter(s -> s.isWasDelayed() || s.getAttempts() > 0).count();
        if (prior.enabled() && delayedBefore > 0) {
            out.add(new RiskAssessment.Contribution(prior.code(), prior.label(), prior.points(),
                delayedBefore + (delayedBefore == 1 ? " shipment" : " shipments") + " already delayed or re-attempted"));
        }

        out.sort((a, b) -> Integer.compare(b.points(), a.points()));
        int score = Math.min(100, out.stream().mapToInt(RiskAssessment.Contribution::points).sum());
        return new RiskAssessment(score, band(score), List.copyOf(out), worstLate, atRisk, late);
    }

    public RiskBand band(int score) {
        if (score >= settings.getInt("risk.band.critical")) {
            return RiskBand.CRITICAL;
        }
        if (score >= settings.getInt("risk.band.high")) {
            return RiskBand.HIGH;
        }
        if (score >= settings.getInt("risk.band.medium")) {
            return RiskBand.MEDIUM;
        }
        return RiskBand.LOW;
    }
}
