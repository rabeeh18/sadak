package com.fleetops.complaint;

import com.fleetops.trip.RiskBand;

public enum ComplaintCategory {
    LATE("Running late", RiskBand.MEDIUM),
    NOT_RECEIVED("Not received", RiskBand.HIGH),
    DAMAGED("Damaged", RiskBand.HIGH),
    WRONG_ADDRESS("Wrong address", RiskBand.MEDIUM),
    DRIVER("Driver behaviour", RiskBand.MEDIUM),
    OTHER("Something else", RiskBand.MEDIUM);

    private final String label;
    private final RiskBand severity;

    ComplaintCategory(String label, RiskBand severity) {
        this.label = label;
        this.severity = severity;
    }

    public String label() {
        return label;
    }

    public RiskBand severity() {
        return severity;
    }
}
