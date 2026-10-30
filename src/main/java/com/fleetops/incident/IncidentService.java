package com.fleetops.incident;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fleetops.audit.AuditFields;
import com.fleetops.audit.AuditService;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.fleet.Vehicle;
import com.fleetops.notification.NotificationService;
import com.fleetops.security.CurrentUser;
import com.fleetops.security.Role;
import com.fleetops.shipment.Shipment;
import com.fleetops.tracking.FleetBroadcaster;
import com.fleetops.trip.RiskBand;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripRepository;

/**
 * Incident lifecycle. Detection is idempotent: an unresolved incident of the same type on the
 * same trip is updated rather than duplicated (a partial unique index enforces it in the database).
 */
@Service
public class IncidentService {

    private final IncidentRepository incidents;
    private final IncidentActionRepository actions;
    private final TripRepository trips;
    private final NotificationService notifications;
    private final FleetBroadcaster broadcaster;
    private final AuditService audit;
    private final OperationalClock clock;
    private final TransactionTemplate requiresNew;

    public IncidentService(IncidentRepository incidents, IncidentActionRepository actions, TripRepository trips,
                           NotificationService notifications, FleetBroadcaster broadcaster, AuditService audit,
                           OperationalClock clock, PlatformTransactionManager txManager) {
        this.incidents = incidents;
        this.actions = actions;
        this.trips = trips;
        this.notifications = notifications;
        this.broadcaster = broadcaster;
        this.audit = audit;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Detection(long tripId, IncidentType type, RiskBand severity, String title, String details,
                            Shipment shipment, Vehicle vehicle) {
    }

    /**
     * Opens a new incident or updates the open one, in its own transaction. If another thread
     * opened the same incident between our lookup and insert, the unique index rejects ours and
     * the retry updates theirs.
     */
    public Incident detect(Detection d) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return requiresNew.execute(status -> upsert(d));
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException race) {
                // Another thread inserted or updated the same open incident first; re-read and apply ours on top.
                last = race;
            }
        }
        throw last;
    }

    private Incident upsert(Detection d) {
        Instant now = clock.now();
        var open = incidents.findOpen(d.tripId(), d.type());
        if (open.isPresent()) {
            Incident existing = open.get();
            boolean worse = existing.redetect(d.severity(), d.title(), d.details(), now);
            if (worse && d.severity().atLeast(RiskBand.HIGH)) {
                notifyDispatchers(existing, "Incident worsened: ");
            }
            publish(existing, "updated");
            return existing;
        }
        Trip trip = trips.getReferenceById(d.tripId());
        Incident created = incidents.saveAndFlush(new Incident("INC-" + incidents.nextCodeNumber(), d.type(), d.severity(),
            trip, d.shipment(), d.vehicle(), d.title(), d.details(), now));
        audit.record("INCIDENT_DETECTED", "INCIDENT", created.getId(), created.getCode() + " " + d.type().label() + ": " + d.title());
        if (d.severity().atLeast(RiskBand.HIGH)) {
            notifyDispatchers(created, "");
        }
        publish(created, "opened");
        return created;
    }

    /**
     * The monitored condition is no longer true. An incident nobody has picked up resolves
     * itself; one a dispatcher is working on is only flagged, so their workflow is not cut short.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void conditionCleared(long tripId, IncidentType type) {
        incidents.findOpen(tripId, type).ifPresent(incident -> {
            if (!type.autoClears() || incident.isConditionCleared()) {
                return;
            }
            Instant now = clock.now();
            if (incident.getStatus() == IncidentStatus.DETECTED) {
                incident.resolve("Condition cleared automatically", now);
                actions.save(new IncidentAction(incident, IncidentAction.Kind.RESOLVED, "Condition cleared automatically", CurrentUser.SYSTEM, now));
                audit.record("INCIDENT_AUTO_RESOLVED", "INCIDENT", incident.getId(), incident.getCode() + " cleared automatically");
            } else {
                incident.markConditionCleared();
                actions.save(new IncidentAction(incident, IncidentAction.Kind.CONDITION_CLEARED,
                    "Monitoring no longer detects this condition", CurrentUser.SYSTEM, now));
            }
            publish(incident, "updated");
        });
    }

    @Transactional
    public void acknowledge(long id) {
        Incident i = get(id);
        IncidentStatus before = i.getStatus();
        i.acknowledge(CurrentUser.username(), clock.now());
        record(i, IncidentAction.Kind.ACKNOWLEDGED, null);
        audit.record("INCIDENT_ACKNOWLEDGED", "INCIDENT", id, i.getCode() + " acknowledged by " + CurrentUser.username(),
            AuditFields.of("status", before), AuditFields.of("status", i.getStatus(), "owner", i.getOwnerUsername()));
        publish(i, "updated");
    }

    /** Records an action taken (contact, ETA change, reassignment...) and moves the incident into progress. */
    @Transactional
    public void recordAction(long id, IncidentAction.Kind kind, String note) {
        Incident i = get(id);
        IncidentStatus before = i.getStatus();
        i.actionTaken(CurrentUser.username(), clock.now());
        record(i, kind, note);
        audit.record("INCIDENT_ACTION", "INCIDENT", id, i.getCode() + " " + kind.label().toLowerCase()
                + (note == null || note.isBlank() ? "" : ": " + note.trim()),
            AuditFields.of("status", before), AuditFields.of("status", i.getStatus()));
        publish(i, "updated");
    }

    @Transactional
    public void escalate(long id, String note) {
        Incident i = get(id);
        RiskBand before = i.getSeverity();
        i.escalate();
        i.actionTaken(CurrentUser.username(), clock.now());
        record(i, IncidentAction.Kind.ESCALATED, note);
        audit.record("INCIDENT_ESCALATED", "INCIDENT", id, i.getCode() + " escalated",
            AuditFields.of("severity", before), AuditFields.of("severity", i.getSeverity()));
        notifications.notifyRoles(List.of(Role.ADMIN), i.getSeverity(), "Escalated: " + i.getCode() + " " + i.getType().label(),
            note == null || note.isBlank() ? i.getTitle() : note.trim(), "/incidents/" + i.getId());
        publish(i, "updated");
    }

    @Transactional
    public void resolve(long id, String resolution) {
        if (resolution == null || resolution.isBlank()) {
            throw new BusinessRuleException("Describe how the incident was resolved");
        }
        Incident i = get(id);
        IncidentStatus before = i.getStatus();
        i.resolve(resolution.trim(), clock.now());
        record(i, IncidentAction.Kind.RESOLVED, resolution.trim());
        audit.record("INCIDENT_RESOLVED", "INCIDENT", id, i.getCode() + " resolved: " + resolution.trim(),
            AuditFields.of("status", before), AuditFields.of("status", IncidentStatus.RESOLVED));
        publish(i, "resolved");
    }

    /** When a trip ends, conditions that only exist while driving can no longer be acted on. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closeTransientForTrip(long tripId) {
        Instant now = clock.now();
        for (Incident i : incidents.findByTripIdAndStatusIn(tripId, EnumSet.of(IncidentStatus.DETECTED))) {
            if (i.getType().autoClears()) {
                i.resolve("Trip ended", now);
                actions.save(new IncidentAction(i, IncidentAction.Kind.RESOLVED, "Trip ended", CurrentUser.SYSTEM, now));
                publish(i, "resolved");
            }
        }
    }

    @Transactional(readOnly = true)
    public Incident get(long id) {
        return incidents.findWithLinksById(id).orElseThrow(() -> new NotFoundException("Incident", id));
    }

    @Transactional(readOnly = true)
    public List<IncidentAction> actionsOf(long id) {
        return actions.findByIncidentIdOrderByPerformedAtAscIdAsc(id);
    }

    @Transactional(readOnly = true)
    public List<Incident> openBySeverity() {
        return incidents.findOpenBySeverity();
    }

    private void record(Incident i, IncidentAction.Kind kind, String note) {
        actions.save(new IncidentAction(i, kind, note == null || note.isBlank() ? null : note.trim(),
            CurrentUser.username(), clock.now()));
    }

    private void notifyDispatchers(Incident i, String prefix) {
        String tripCode = i.getTrip() != null ? i.getTrip().getCode() : "";
        notifications.notifyRoles(List.of(Role.DISPATCHER, Role.ADMIN), i.getSeverity(),
            prefix + i.getType().label() + " · " + tripCode, i.getTitle(), "/incidents/" + i.getId());
    }

    private void publish(Incident i, String change) {
        Map<String, Object> payload = Map.of(
            "id", i.getId(), "code", i.getCode(), "type", i.getType().name(), "typeLabel", i.getType().label(),
            "severity", i.getSeverity().name(), "status", i.getStatus().name(), "title", i.getTitle(),
            "tripId", i.getTrip() == null ? 0 : i.getTrip().getId(), "change", change);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcaster.publishIncident(payload);
                }
            });
        } else {
            broadcaster.publishIncident(payload);
        }
    }
}
