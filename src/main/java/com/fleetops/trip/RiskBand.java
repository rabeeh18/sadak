package com.fleetops.trip;

public enum RiskBand {
    // A trip's light. The names are kept from the earlier scoring bands, so stored data stays valid.
    LOW("On track"),
    MEDIUM("Watch"),
    HIGH("Needs action");

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
