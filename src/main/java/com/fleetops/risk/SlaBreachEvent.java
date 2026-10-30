package com.fleetops.risk;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sla_breach_events")
public class SlaBreachEvent {

    public enum Kind { LATE_DELIVERY, FAILED, DEADLINE_PASSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shipment_id", nullable = false)
    private Long shipmentId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(nullable = false)
    private Instant deadline;

    @Column(name = "breached_at", nullable = false)
    private Instant breachedAt;

    @Column(name = "minutes_late")
    private int minutesLate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    protected SlaBreachEvent() {
    }

    public SlaBreachEvent(Long shipmentId, Long tripId, Instant deadline, Instant breachedAt, int minutesLate, Kind kind) {
        this.shipmentId = shipmentId;
        this.tripId = tripId;
        this.deadline = deadline;
        this.breachedAt = breachedAt;
        this.minutesLate = minutesLate;
        this.kind = kind;
    }

    public Long getId() {
        return id;
    }

    public Long getShipmentId() {
        return shipmentId;
    }

    public Long getTripId() {
        return tripId;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public Instant getBreachedAt() {
        return breachedAt;
    }

    public int getMinutesLate() {
        return minutesLate;
    }

    public Kind getKind() {
        return kind;
    }
}
