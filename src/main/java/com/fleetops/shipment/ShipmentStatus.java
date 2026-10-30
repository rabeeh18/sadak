package com.fleetops.shipment;

import java.util.EnumSet;
import java.util.Set;

public enum ShipmentStatus {
    CREATED("Awaiting planning"),
    PLANNED("Planned on a trip"),
    OUT_FOR_DELIVERY("Out for delivery"),
    DELIVERED("Delivered"),
    FAILED("Delivery failed"),
    CANCELLED("Cancelled");

    private final String label;

    ShipmentStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isFinal() {
        return this == DELIVERED || this == CANCELLED;
    }

    /** Allowed lifecycle transitions; a failed shipment may be re-planned for another attempt. */
    public boolean canMoveTo(ShipmentStatus next) {
        return allowedNext().contains(next);
    }

    private Set<ShipmentStatus> allowedNext() {
        return switch (this) {
            case CREATED -> EnumSet.of(PLANNED, CANCELLED);
            case PLANNED -> EnumSet.of(OUT_FOR_DELIVERY, CREATED, CANCELLED);
            case OUT_FOR_DELIVERY -> EnumSet.of(DELIVERED, FAILED, PLANNED);
            case FAILED -> EnumSet.of(CREATED, CANCELLED);
            case DELIVERED, CANCELLED -> EnumSet.noneOf(ShipmentStatus.class);
        };
    }
}
