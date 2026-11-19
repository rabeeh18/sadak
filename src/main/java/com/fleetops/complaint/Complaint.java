package com.fleetops.complaint;

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
import com.fleetops.shipment.Shipment;

/**
 * A problem a customer reported from the tracking page. It goes to the desk of the warehouse the
 * parcel left from; dispatchers see every complaint and can be asked to step in.
 */
@Entity
@Table(name = "complaints")
public class Complaint {

    public enum Status { NEW, IN_PROGRESS, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id")
    private Shipment shipment;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComplaintCategory category;

    @Column(nullable = false)
    private String message;

    @Column(name = "callback_phone", nullable = false)
    private String callbackPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.NEW;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "handled_by")
    private String handledBy;

    @Column(name = "taken_at")
    private Instant takenAt;

    @Column(name = "sent_to_dispatch")
    private boolean sentToDispatch;

    private String reply;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    private int version;

    protected Complaint() {
    }

    public Complaint(String code, Shipment shipment, ComplaintCategory category, String message, String callbackPhone, Instant at) {
        this.code = code;
        this.shipment = shipment;
        this.warehouseId = shipment.getOriginWarehouse().getId();
        this.category = category;
        this.message = message;
        this.callbackPhone = callbackPhone;
        this.createdAt = at;
    }

    /** Someone at the desk (or a dispatcher) picks it up. */
    public void take(String by, Instant at) {
        requireOpen();
        status = Status.IN_PROGRESS;
        handledBy = by;
        if (takenAt == null) {
            takenAt = at;
        }
    }

    public void sendToDispatch() {
        requireOpen();
        if (sentToDispatch) {
            throw new BusinessRuleException(code + " has already been sent to dispatch");
        }
        sentToDispatch = true;
    }

    public void resolve(String by, String reply, Instant at) {
        requireOpen();
        if (takenAt == null) {
            takenAt = at;
        }
        handledBy = by;
        this.reply = reply;
        this.status = Status.RESOLVED;
        this.resolvedAt = at;
    }

    private void requireOpen() {
        if (status == Status.RESOLVED) {
            throw new BusinessRuleException(code + " is already resolved");
        }
    }

    public boolean isOpen() {
        return status != Status.RESOLVED;
    }

    /** The customer's number with the middle hidden, for the public tracking page. */
    public String maskedPhone() {
        String p = callbackPhone;
        return p.length() <= 5 ? p : p.substring(0, 2) + "x".repeat(p.length() - 5) + p.substring(p.length() - 3);
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Shipment getShipment() {
        return shipment;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public ComplaintCategory getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }

    public String getCallbackPhone() {
        return callbackPhone;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    public boolean isSentToDispatch() {
        return sentToDispatch;
    }

    public String getReply() {
        return reply;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
