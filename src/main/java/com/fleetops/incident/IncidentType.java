package com.fleetops.incident;

public enum IncidentType {
    SLA_RISK("Delivery at risk of missing deadline", true),
    BREAKDOWN("Vehicle breakdown", false),
    LONG_STOP("Unexpectedly long stop", true),
    ROUTE_DEVIATION("Off planned route", true),
    LOCATION_STALE("Missing location updates", true),
    FAILED_DELIVERY("Failed delivery", false),
    RESOURCE_UNAVAILABLE("Driver or vehicle unavailable", false);

    private final String label;
    private final boolean autoClears;

    IncidentType(String label, boolean autoClears) {
        this.label = label;
        this.autoClears = autoClears;
    }

    public String label() {
        return label;
    }

    /** Monitoring-detected conditions clear themselves; reported events need a person to resolve them. */
    public boolean autoClears() {
        return autoClears;
    }
}
