package com.fleetops.incident;

public enum IncidentStatus {
    DETECTED("Open"),
    IN_PROGRESS("Being handled"),
    RESOLVED("Closed");

    private final String label;

    IncidentStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isOpen() {
        return this != RESOLVED;
    }
}
