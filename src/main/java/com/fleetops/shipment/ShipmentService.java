package com.fleetops.shipment;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.audit.AuditService;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.fleet.FleetService;
import com.fleetops.fleet.Warehouse;
import com.fleetops.history.StatusHistoryService;

@Service
public class ShipmentService {

    private final ShipmentRepository shipments;
    private final FleetService fleet;
    private final StatusHistoryService history;
    private final AuditService audit;
    private final OperationalClock clock;

    public ShipmentService(ShipmentRepository shipments, FleetService fleet, StatusHistoryService history,
                           AuditService audit, OperationalClock clock) {
        this.shipments = shipments;
        this.fleet = fleet;
        this.history = history;
        this.audit = audit;
        this.clock = clock;
    }

    public record NewShipment(long warehouseId, String recipientName, String recipientPhone, String destName,
                              String destAddress, String destPin, double destLat, double destLng,
                              int weightKg, Instant deadline) {
    }

    @Transactional
    public Shipment create(NewShipment req) {
        Instant now = clock.now();
        if (req.weightKg() <= 0) {
            throw new BusinessRuleException("Weight must be more than 0 kg");
        }
        if (req.destPin() == null || !req.destPin().matches("\\d{6}")) {
            throw new BusinessRuleException("PIN code must be 6 digits");
        }
        if (req.deadline() == null || !req.deadline().isAfter(now)) {
            throw new BusinessRuleException("The delivery deadline must be in the future");
        }
        Warehouse origin = fleet.warehouse(req.warehouseId());
        Shipment s = shipments.save(new Shipment(nextTrackingNo(), origin, req.recipientName().trim(),
            blankToNull(req.recipientPhone()), req.destName().trim(), req.destAddress().trim(), req.destPin(),
            req.destLat(), req.destLng(), req.weightKg(), req.deadline(), now));
        history.record(StatusHistoryService.SHIPMENT, s.getId(), null, ShipmentStatus.CREATED, "Booked");
        audit.record("SHIPMENT_CREATED", "SHIPMENT", s.getId(), "Shipment " + s.getTrackingNo() + " booked for " + s.getDestName());
        return s;
    }

    public String nextTrackingNo() {
        return "FL" + shipments.nextTrackingNumber();
    }

    /** Puts a failed shipment back in the planning queue for another attempt. */
    @Transactional
    public void requeue(long shipmentId, String reason) {
        Shipment s = get(shipmentId);
        ShipmentStatus old = s.transition(ShipmentStatus.CREATED);
        s.unassign();
        history.record(StatusHistoryService.SHIPMENT, shipmentId, old, ShipmentStatus.CREATED, reason);
        audit.record("SHIPMENT_REQUEUED", "SHIPMENT", shipmentId, s.getTrackingNo() + " re-queued for another attempt");
    }

    @Transactional
    public void cancel(long shipmentId, String reason) {
        Shipment s = get(shipmentId);
        if (s.getStatus() != ShipmentStatus.CREATED && s.getStatus() != ShipmentStatus.FAILED) {
            throw new BusinessRuleException("Only unplanned or failed shipments can be cancelled; remove it from its trip first");
        }
        ShipmentStatus old = s.transition(ShipmentStatus.CANCELLED);
        history.record(StatusHistoryService.SHIPMENT, shipmentId, old, ShipmentStatus.CANCELLED, reason);
        audit.record("SHIPMENT_CANCELLED", "SHIPMENT", shipmentId, s.getTrackingNo() + " cancelled"
            + (reason == null || reason.isBlank() ? "" : ": " + reason));
    }

    @Transactional(readOnly = true)
    public Shipment get(long id) {
        return shipments.findById(id).orElseThrow(() -> new NotFoundException("Shipment", id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
