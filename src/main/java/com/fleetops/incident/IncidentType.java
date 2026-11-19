package com.fleetops.incident;

/**
 * The five problems that need a person. Each has one obvious fix, offered as the main button.
 * (Failed deliveries are not incidents: the parcel's warehouse desk rebooks them.)
 */
public enum IncidentType {
    SLA_RISK("Will be late", "Give customer a new time", true),
    BREAKDOWN("Breakdown", "Send replacement truck", false),
    LONG_STOP("Truck stuck", "Call the driver", true),
    ROUTE_DEVIATION("Off route", "Call the driver", true),
    LOCATION_STALE("GPS lost", "Call the driver", true),
    NOT_DEPARTED("Hasn't left yet", "Call the driver", true);

    private final String label;
    private final String fix;
    private final boolean autoClears;

    IncidentType(String label, String fix, boolean autoClears) {
        this.label = label;
        this.fix = fix;
        this.autoClears = autoClears;
    }

    public String label() {
        return label;
    }

    /** The main button on this incident. */
    public String fix() {
        return fix;
    }

    public boolean callsDriver() {
        return fix.equals("Call the driver");
    }

    /** Detected conditions close themselves when they clear; a reported breakdown needs a person. */
    public boolean autoClears() {
        return autoClears;
    }
}
