package com.fleetops.trip;

public enum StopStatus {
    PENDING("Upcoming"),
    ARRIVED("At stop"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    SKIPPED("Skipped");

    private final String label;

    StopStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isDone() {
        return this == COMPLETED || this == FAILED || this == SKIPPED;
    }
}
