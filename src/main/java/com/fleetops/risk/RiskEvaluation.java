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

import com.fleetops.trip.RiskBand;

/** A stored point in a trip's risk history; written when the score moves materially. */
@Entity
@Table(name = "risk_evaluations")
public class RiskEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    private int score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskBand band;

    @Column(nullable = false)
    private String reasons;

    protected RiskEvaluation() {
    }

    public RiskEvaluation(Long tripId, Instant evaluatedAt, int score, RiskBand band, String reasons) {
        this.tripId = tripId;
        this.evaluatedAt = evaluatedAt;
        this.score = score;
        this.band = band;
        this.reasons = reasons;
    }

    public Long getId() {
        return id;
    }

    public Long getTripId() {
        return tripId;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public int getScore() {
        return score;
    }

    public RiskBand getBand() {
        return band;
    }

    public String getReasons() {
        return reasons;
    }
}
