package com.fleetops.trip;

/**
 * Domain events published by {@link TripService}. Listeners in tracking, risk and incidents
 * react after the transaction commits, so they always see the committed state.
 */
public final class TripEvents {

    private TripEvents() {
    }

    /** The trip started, or its active route changed; tracking must (re)load it. */
    public record RouteActivated(long tripId) {
    }

    /** The trip ended (completed or cancelled); tracking drops it. */
    public record TripEnded(long tripId) {
    }

    public record StopArrived(long tripId, long stopId) {
    }

    public record StopCompleted(long tripId, long stopId) {
    }

    public record StopFailed(long tripId, long stopId, String reason) {
    }

    public record VehicleBrokeDown(long tripId, long vehicleId, String note) {
    }

    /** Something about the trip changed that dispatch screens should refresh. */
    public record TripChanged(long tripId) {
    }
}
