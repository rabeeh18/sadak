package com.fleetops.fleet;

public enum VehicleStatus {
    AVAILABLE("Available"),
    ON_TRIP("On trip"),
    BROKEN_DOWN("Broken down"),
    MAINTENANCE("In workshop"),
    RETIRED("Retired");

    private final String label;

    VehicleStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
