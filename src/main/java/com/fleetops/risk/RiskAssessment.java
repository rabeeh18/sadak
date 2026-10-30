package com.fleetops.risk;

import java.util.List;
import java.util.stream.Collectors;

import com.fleetops.trip.RiskBand;

/** The outcome of scoring one trip: the total, its band, and every rule that contributed. */
public record RiskAssessment(int score, RiskBand band, List<Contribution> contributions,
                             int worstLateMinutes, int shipmentsAtRisk, int shipmentsLate) {

    public record Contribution(String code, String label, int points, String detail) {
    }

    public boolean has(String code) {
        return contributions.stream().anyMatch(c -> c.code().equals(code));
    }

    public Contribution get(String code) {
        return contributions.stream().filter(c -> c.code().equals(code)).findFirst().orElse(null);
    }

    /** Stored on the trip and shown next to the score: "+40 Projected late (25 min) · +30 ..." */
    public String explanation() {
        if (contributions.isEmpty()) {
            return "No risk conditions";
        }
        return contributions.stream().map(c -> "+" + c.points() + " " + c.label() + " — " + c.detail())
            .collect(Collectors.joining("\n"));
    }
}
