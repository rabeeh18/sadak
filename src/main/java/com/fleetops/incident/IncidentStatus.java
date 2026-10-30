package com.fleetops.incident;

public enum IncidentStatus {
    DETECTED("Detected"),
    ACKNOWLEDGED("Acknowledged"),
    IN_PROGRESS("Action in progress"),
    RESOLVED("Resolved");

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
