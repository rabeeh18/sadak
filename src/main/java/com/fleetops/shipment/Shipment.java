package com.fleetops.shipment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.fleetops.common.geo.GeoPoint;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.fleet.Warehouse;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripStop;

@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracking_no", nullable = false, unique = true)
    private String trackingNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "origin_warehouse_id")
    private Warehouse originWarehouse;

    @Column(name = "recipient_name", nullable = false)
    private String recipientName;

    @Column(name = "recipient_phone")
    private String recipientPhone;

    @Column(name = "dest_name", nullable = false)
    private String destName;

    @Column(name = "dest_address", nullable = false)
    private String destAddress;

    @Column(name = "dest_pin", nullable = false)
    private String destPin;

    @Column(name = "dest_lat")
    private double destLat;

    @Column(name = "dest_lng")
    private double destLng;

    @Column(name = "weight_kg")
    private int weightKg;

    @Column(nullable = false)
    private Instant deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status = ShipmentStatus.CREATED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stop_id")
    private TripStop stop;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "failure_reason")
    private String failureReason;

    private int attempts;

    @Column(name = "was_delayed")
    private boolean wasDelayed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Shipment() {
    }

    public Shipment(String trackingNo, Warehouse origin, String recipientName, String recipientPhone,
                    String destName, String destAddress, String destPin, double destLat, double destLng,
                    int weightKg, Instant deadline, Instant createdAt) {
        this.trackingNo = trackingNo;
        this.originWarehouse = origin;
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.destName = destName;
        this.destAddress = destAddress;
        this.destPin = destPin;
        this.destLat = destLat;
        this.destLng = destLng;
        this.weightKg = weightKg;
        this.deadline = deadline;
        this.createdAt = createdAt;
    }

    /** Moves through the lifecycle, refusing transitions the state machine does not allow. */
    public ShipmentStatus transition(ShipmentStatus next) {
        if (!status.canMoveTo(next)) {
            throw new BusinessRuleException("Shipment " + trackingNo + " cannot go from "
                + status.label().toLowerCase() + " to " + next.label().toLowerCase());
        }
        ShipmentStatus old = status;
        status = next;
        return old;
    }

    public void assignTo(Trip trip, TripStop stop) {
        this.trip = trip;
        this.stop = stop;
    }

    public void unassign() {
        this.trip = null;
        this.stop = null;
    }

    public void markDelivered(Instant at) {
        transition(ShipmentStatus.DELIVERED);
        this.deliveredAt = at;
        this.attempts++;
        if (at.isAfter(deadline)) {
            this.wasDelayed = true;
        }
    }

    public void markFailed(String reason) {
        transition(ShipmentStatus.FAILED);
        this.failureReason = reason;
        this.attempts++;
        this.wasDelayed = true;
    }

    public void flagDelayed() {
        this.wasDelayed = true;
    }

    /** A new promised delivery time agreed with the customer. */
    public void changeDeadline(Instant newDeadline) {
        if (status.isFinal()) {
            throw new BusinessRuleException("Shipment " + trackingNo + " is already " + status.label().toLowerCase());
        }
        this.deadline = newDeadline;
    }

    public GeoPoint destination() {
        return new GeoPoint(destLat, destLng);
    }

    public Long getId() {
        return id;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public Warehouse getOriginWarehouse() {
        return originWarehouse;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getRecipientPhone() {
        return recipientPhone;
    }

    public String getDestName() {
        return destName;
    }

    public String getDestAddress() {
        return destAddress;
    }

    public String getDestPin() {
        return destPin;
    }

    public double getDestLat() {
        return destLat;
    }

    public double getDestLng() {
        return destLng;
    }

    public int getWeightKg() {
        return weightKg;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public Trip getTrip() {
        return trip;
    }

    public TripStop getStop() {
        return stop;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttempts() {
        return attempts;
    }

    public boolean isWasDelayed() {
        return wasDelayed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** For the seeder: lets history be written with past timestamps. */
    public void restoreHistory(ShipmentStatus status, Instant deliveredAt, String failureReason, int attempts, boolean wasDelayed) {
        this.status = status;
        this.deliveredAt = deliveredAt;
        this.failureReason = failureReason;
        this.attempts = attempts;
        this.wasDelayed = wasDelayed;
    }
}
