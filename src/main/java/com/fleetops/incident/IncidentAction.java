package com.fleetops.incident;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "incident_actions")
public class IncidentAction {

    /** Things a dispatcher (or the system) can record against an incident. */
    public enum Kind {
        ACKNOWLEDGED("Acknowledged"),
        CONTACTED_DRIVER("Contacted driver"),
        ADJUSTED_ETA("Adjusted ETA"),
        RECALCULATED_ROUTE("Recalculated route"),
        REASSIGNED("Reassigned vehicle/driver"),
        MARKED_FAILED("Marked delivery failed"),
        ESCALATED("Escalated"),
        NOTE("Note"),
        CONDITION_CLEARED("Condition cleared"),
        RESOLVED("Resolved");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @Column(nullable = false)
    private String action;

    private String note;

    @Column(nullable = false)
    private String actor;

    @Column(name = "performed_at", nullable = false)
    private Instant performedAt;

    protected IncidentAction() {
    }

    public IncidentAction(Incident incident, Kind kind, String note, String actor, Instant performedAt) {
        this.incident = incident;
        this.action = kind.name();
        this.note = note;
        this.actor = actor;
        this.performedAt = performedAt;
    }

    public Long getId() {
        return id;
    }

    public Incident getIncident() {
        return incident;
    }

    public Kind getKind() {
        return Kind.valueOf(action);
    }

    public String getNote() {
        return note;
    }

    public String getActor() {
        return actor;
    }

    public Instant getPerformedAt() {
        return performedAt;
    }
}
