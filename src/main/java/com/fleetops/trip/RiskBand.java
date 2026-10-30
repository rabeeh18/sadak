package com.fleetops.trip;

public enum RiskBand {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    CRITICAL("Critical");

    private final String label;

    RiskBand(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean atLeast(RiskBand other) {
        return ordinal() >= other.ordinal();
    }
}
