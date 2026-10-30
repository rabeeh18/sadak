package com.fleetops.trip;

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
@Table(name = "delivery_proofs")
public class DeliveryProof {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stop_id")
    private TripStop stop;

    @Column(name = "recipient_name", nullable = false)
    private String recipientName;

    private String note;

    private Double lat;
    private Double lng;

    @Column(name = "captured_by")
    private String capturedBy;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected DeliveryProof() {
    }

    public DeliveryProof(TripStop stop, String recipientName, String note, Double lat, Double lng,
                         String capturedBy, Instant capturedAt) {
        this.stop = stop;
        this.recipientName = recipientName;
        this.note = note;
        this.lat = lat;
        this.lng = lng;
        this.capturedBy = capturedBy;
        this.capturedAt = capturedAt;
    }

    public Long getId() {
        return id;
    }

    public TripStop getStop() {
        return stop;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getNote() {
        return note;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLng() {
        return lng;
    }

    public String getCapturedBy() {
        return capturedBy;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
