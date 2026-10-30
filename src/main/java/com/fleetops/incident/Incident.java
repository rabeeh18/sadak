package com.fleetops.incident;

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
import jakarta.persistence.Version;

import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.fleet.Vehicle;
import com.fleetops.shipment.Shipment;
import com.fleetops.trip.RiskBand;
import com.fleetops.trip.Trip;

@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskBand severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status = IncidentStatus.DETECTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id")
    private Shipment shipment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @Column(nullable = false)
    private String title;

    private String details;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "last_detected_at", nullable = false)
    private Instant lastDetectedAt;

    @Column(name = "detection_count")
    private int detectionCount = 1;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "owner_username")
    private String ownerUsername;

    private boolean escalated;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    private String resolution;

    @Column(name = "condition_cleared")
    private boolean conditionCleared;

    @Version
    private int version;

    protected Incident() {
    }

    public Incident(String code, IncidentType type, RiskBand severity, Trip trip, Shipment shipment, Vehicle vehicle,
                    String title, String details, Instant at) {
        this.code = code;
        this.type = type;
        this.severity = severity;
        this.trip = trip;
        this.shipment = shipment;
        this.vehicle = vehicle;
        this.title = title;
        this.details = details;
        this.detectedAt = at;
        this.lastDetectedAt = at;
    }

    /** A repeat detection updates the open incident instead of creating a new one. */
    public boolean redetect(RiskBand newSeverity, String newTitle, String newDetails, Instant at) {
        boolean worse = newSeverity.ordinal() > severity.ordinal();
        if (worse || status == IncidentStatus.DETECTED) {
            severity = newSeverity;
        }
        title = newTitle;
        details = newDetails;
        lastDetectedAt = at;
        detectionCount++;
        conditionCleared = false;
        return worse;
    }

    public void acknowledge(String by, Instant at) {
        requireOpen();
        if (status == IncidentStatus.DETECTED) {
            status = IncidentStatus.ACKNOWLEDGED;
            acknowledgedAt = at;
        }
        ownerUsername = by;
    }

    public void actionTaken(String by, Instant at) {
        requireOpen();
        if (acknowledgedAt == null) {
            acknowledgedAt = at;
        }
        if (ownerUsername == null) {
            ownerUsername = by;
        }
        status = IncidentStatus.IN_PROGRESS;
    }

    public void escalate() {
        requireOpen();
        escalated = true;
        if (severity != RiskBand.CRITICAL) {
            severity = RiskBand.values()[severity.ordinal() + 1];
        }
    }

    public void resolve(String resolution, Instant at) {
        requireOpen();
        this.status = IncidentStatus.RESOLVED;
        this.resolution = resolution;
        this.resolvedAt = at;
    }

    public void markConditionCleared() {
        this.conditionCleared = true;
    }

    private void requireOpen() {
        if (status == IncidentStatus.RESOLVED) {
            throw new BusinessRuleException(code + " is already resolved");
        }
    }

    public Duration openFor(Instant now) {
        return Duration.between(detectedAt, resolvedAt != null ? resolvedAt : now);
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public IncidentType getType() {
        return type;
    }

    public RiskBand getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Trip getTrip() {
        return trip;
    }

    public Shipment getShipment() {
        return shipment;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public String getTitle() {
        return title;
    }

    public String getDetails() {
        return details;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getLastDetectedAt() {
        return lastDetectedAt;
    }

    public int getDetectionCount() {
        return detectionCount;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public boolean isEscalated() {
        return escalated;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolution() {
        return resolution;
    }

    public boolean isConditionCleared() {
        return conditionCleared;
    }

    /** For the seeder: writes a resolved incident with past timestamps. */
    public void restoreHistory(IncidentStatus status, Instant acknowledgedAt, Instant resolvedAt, String owner, String resolution) {
        this.status = status;
        this.acknowledgedAt = acknowledgedAt;
        this.resolvedAt = resolvedAt;
        this.ownerUsername = owner;
        this.resolution = resolution;
        this.lastDetectedAt = detectedAt;
    }
}
