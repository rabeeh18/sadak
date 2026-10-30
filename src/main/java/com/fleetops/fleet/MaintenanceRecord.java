package com.fleetops.fleet;

import java.time.Duration;
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

@Entity
@Table(name = "maintenance_records")
public class MaintenanceRecord {

    public enum Kind { BREAKDOWN, SCHEDULED, INSPECTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private String reason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "created_by")
    private String createdBy;

    protected MaintenanceRecord() {
    }

    public MaintenanceRecord(Vehicle vehicle, Kind kind, String reason, Instant startedAt, String createdBy) {
        this.vehicle = vehicle;
        this.kind = kind;
        this.reason = reason;
        this.startedAt = startedAt;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public Kind getKind() {
        return kind;
    }

    public String getReason() {
        return reason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public boolean isOpen() {
        return endedAt == null;
    }

    public void close(Instant at) {
        this.endedAt = at;
    }

    public Duration durationUntil(Instant now) {
        return Duration.between(startedAt, endedAt != null ? endedAt : now);
    }
}
