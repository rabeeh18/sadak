package com.fleetops.trip;

public enum TripStatus {
    PLANNED("Planned"),
    IN_PROGRESS("In progress"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String label;

    TripStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isLive() {
        return this == PLANNED || this == IN_PROGRESS;
    }
}
