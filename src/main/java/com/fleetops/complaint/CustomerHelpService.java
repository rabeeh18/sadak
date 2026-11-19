package com.fleetops.complaint;

import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.audit.AuditFields;
import com.fleetops.audit.AuditService;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.notification.NotificationService;
import com.fleetops.security.CurrentUser;
import com.fleetops.security.Role;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.shipment.ShipmentStatus;

/**
 * What customers can do from the tracking page besides looking: leave a note for the driver and
 * report a problem. Complaints go to the parcel's warehouse desk; dispatchers are told as well.
 */
@Service
public class CustomerHelpService {

    public static final int NOTE_MAX = 200;
    public static final int MESSAGE_MAX = 500;
    /** Complaints about a delivered parcel are accepted for this long afterwards. */
    public static final Duration AFTER_DELIVERY = Duration.ofDays(7);

    private static final List<Role> CONTROL_ROOM = List.of(Role.DISPATCHER, Role.ADMIN);

    private final ShipmentRepository shipments;
    private final ComplaintRepository complaints;
    private final NotificationService notifications;
    private final AuditService audit;
    private final OperationalClock clock;

    public CustomerHelpService(ShipmentRepository shipments, ComplaintRepository complaints,
                               NotificationService notifications, AuditService audit, OperationalClock clock) {
        this.shipments = shipments;
        this.complaints = complaints;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ customers

    /** Sets, changes or (when blank) removes the customer's note for the driver. */
    @Transactional
    public void leaveNote(long shipmentId, String note) {
        Shipment s = shipment(shipmentId);
        String text = note == null ? "" : note.strip().replaceAll("\\s+", " ");
        if (text.length() > NOTE_MAX) {
            throw new BusinessRuleException("Keep the note to " + NOTE_MAX + " characters");
        }
        if (s.getStatus() == ShipmentStatus.FAILED) {
            throw new BusinessRuleException("This delivery attempt is over. Call the warehouse desk to arrange the next one.");
        }
        s.leaveNote(text.isEmpty() ? null : text, clock.now());
        audit.record(text.isEmpty() ? "CUSTOMER_NOTE_REMOVED" : "CUSTOMER_NOTE", "SHIPMENT", shipmentId,
            text.isEmpty() ? "Customer removed their note for " + s.getTrackingNo() : "Customer note for " + s.getTrackingNo() + ": " + text);
    }

    @Transactional
    public Complaint file(long shipmentId, ComplaintCategory category, String message, String phone) {
        Shipment s = shipment(shipmentId);
        if (category == null) {
            throw new BusinessRuleException("Pick what went wrong");
        }
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) {
            throw new BusinessRuleException("Tell us a little about what went wrong");
        }
        if (text.length() > MESSAGE_MAX) {
            throw new BusinessRuleException("Keep it to " + MESSAGE_MAX + " characters");
        }
        String number = phoneNumber(phone);
        if (s.getStatus() == ShipmentStatus.CANCELLED) {
            throw new BusinessRuleException("This parcel was cancelled. Call the warehouse desk about it.");
        }
        if (s.getStatus() == ShipmentStatus.DELIVERED && s.getDeliveredAt() != null
            && s.getDeliveredAt().plus(AFTER_DELIVERY).isBefore(clock.now())) {
            throw new BusinessRuleException("This parcel was delivered more than 7 days ago. Call the warehouse desk about it.");
        }
        if (complaints.existsByShipmentIdAndStatusNot(shipmentId, Complaint.Status.RESOLVED)) {
            throw new BusinessRuleException("You already have an open complaint about this parcel. We'll call you back.");
        }
        String code = "SR-" + String.format("%04d", complaints.nextCode());
        Complaint c = complaints.save(new Complaint(code, s, category, text, number, clock.now()));
        audit.record("COMPLAINT_FILED", "SHIPMENT", shipmentId, code + " filed by the customer: " + category.label(),
            null, AuditFields.of("complaint", code, "category", category, "warehouse", s.getOriginWarehouse().getCode()));
        notifications.notifyRoles(CONTROL_ROOM, category.severity(), "Customer complaint " + code,
            category.label() + " · " + s.getTrackingNo() + " · " + s.getOriginWarehouse().getName(), "/complaints/" + c.getId());
        return c;
    }

    // ------------------------------------------------------------------ staff

    @Transactional
    public Complaint take(long id) {
        Complaint c = get(id);
        c.take(CurrentUser.username(), clock.now());
        audit.record("COMPLAINT_TAKEN", "SHIPMENT", c.getShipment().getId(), c.getCode() + " picked up");
        return c;
    }

    @Transactional
    public Complaint sendToDispatch(long id, String note) {
        Complaint c = get(id);
        c.sendToDispatch();
        String why = note == null || note.isBlank() ? null : note.strip();
        audit.record("COMPLAINT_TO_DISPATCH", "SHIPMENT", c.getShipment().getId(),
            c.getCode() + " sent to dispatch" + (why == null ? "" : ": " + why));
        notifications.notifyRoles(CONTROL_ROOM, c.getCategory().severity(), c.getCode() + " needs dispatch",
            (why == null ? c.getCategory().label() : why) + " · " + c.getShipment().getTrackingNo(), "/complaints/" + c.getId());
        return c;
    }

    @Transactional
    public Complaint resolve(long id, String reply) {
        Complaint c = get(id);
        String text = reply == null ? "" : reply.strip();
        if (text.isEmpty()) {
            throw new BusinessRuleException("Write a reply for the customer: it is shown on their tracking page");
        }
        if (text.length() > MESSAGE_MAX) {
            throw new BusinessRuleException("Keep the reply to " + MESSAGE_MAX + " characters");
        }
        c.resolve(CurrentUser.username(), text, clock.now());
        audit.record("COMPLAINT_RESOLVED", "SHIPMENT", c.getShipment().getId(), c.getCode() + " resolved: " + text);
        return c;
    }

    @Transactional(readOnly = true)
    public Complaint get(long id) {
        return complaints.findWithShipmentById(id).orElseThrow(() -> new NotFoundException("Complaint", id));
    }

    // ------------------------------------------------------------------ helpers

    private Shipment shipment(long id) {
        return shipments.findById(id).orElseThrow(() -> new NotFoundException("Shipment", id));
    }

    /** Indian numbers, with or without +91, spaces or dashes. */
    static String phoneNumber(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[\\s()-]", "");
        if (!digits.matches("\\+?\\d{10,13}")) {
            throw new BusinessRuleException("Enter a phone number we can call you back on, for example 98765 43210");
        }
        return digits;
    }
}
