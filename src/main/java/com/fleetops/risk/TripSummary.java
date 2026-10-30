package com.fleetops.risk;

import java.time.Instant;
import java.util.List;

/** What the control tower shows for one live trip. Built by monitoring, pushed over STOMP. */
public record TripSummary(
    long tripId, String code, String status, long vehicleId, String registration, String driver,
    String warehouse, String route, int stopsDone, int stopsTotal, int progress,
    String nextStop, Instant nextEta, Integer deviationMin, Integer lateMin, int shipmentsAtRisk,
    int riskScore, String riskBand, List<RiskAssessment.Contribution> reasons,
    String condition, String grid, Double lat, Double lng, double pace, String scenario) {
}
