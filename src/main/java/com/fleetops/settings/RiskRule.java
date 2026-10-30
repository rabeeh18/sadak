package com.fleetops.settings;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One configurable condition of the rule-based risk score. */
@Entity
@Table(name = "risk_rules")
public class RiskRule {

    @Id
    private String code;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String description;

    private int points;

    private Double threshold;

    private String unit;

    private boolean enabled;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected RiskRule() {
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public int getPoints() {
        return points;
    }

    public Double getThreshold() {
        return threshold;
    }

    public String getUnit() {
        return unit;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void update(int points, Double threshold, boolean enabled, String by, Instant at) {
        this.points = points;
        this.threshold = threshold;
        this.enabled = enabled;
        this.updatedBy = by;
        this.updatedAt = at;
    }
}
