package com.fleetops.fleet;

public enum DriverStatus {
    AVAILABLE("Available"),
    ON_TRIP("On trip"),
    OFF_DUTY("Off duty"),
    UNAVAILABLE("Unavailable");

    private final String label;

    DriverStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
