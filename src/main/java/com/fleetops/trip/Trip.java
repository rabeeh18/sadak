package com.fleetops.trip;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.Vehicle;
import com.fleetops.fleet.Warehouse;

/**
 * One execution of a delivery route. The trip owns the current vehicle and driver assignment;
 * {@code trip_assignments} keeps the history of every change.
 */
@Entity
@Table(name = "trips")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripStatus status = TripStatus.PLANNED;

    @Column(name = "planned_start", nullable = false)
    private Instant plannedStart;

    @Column(name = "planned_end")
    private Instant plannedEnd;

    @Column(name = "actual_start")
    private Instant actualStart;

    @Column(name = "actual_end")
    private Instant actualEnd;

    @Column(name = "total_weight_kg")
    private int totalWeightKg;

    @Column(name = "delay_adjust_min")
    private int delayAdjustMin;

    @Column(name = "auto_complete_stops")
    private boolean autoCompleteStops = true;

    @Column(name = "risk_score")
    private int riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_band", nullable = false)
    private RiskBand riskBand = RiskBand.LOW;

    @Column(name = "risk_reasons")
    private String riskReasons;

    @Column(name = "risk_evaluated_at")
    private Instant riskEvaluatedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private int version;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq asc")
    private List<TripStop> stops = new ArrayList<>();

    protected Trip() {
    }

    public Trip(String code, Warehouse warehouse, Vehicle vehicle, Driver driver, Instant plannedStart,
                String createdBy, Instant createdAt) {
        this.code = code;
        this.warehouse = warehouse;
        this.vehicle = vehicle;
        this.driver = driver;
        this.plannedStart = plannedStart;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public TripStop addStop(TripStop stop) {
        stop.attachTo(this, stops.size() + 1);
        stops.add(stop);
        return stop;
    }

    public void start(Instant at) {
        if (status != TripStatus.PLANNED) {
            throw new BusinessRuleException("Trip " + code + " is " + status.label().toLowerCase() + " and cannot be started");
        }
        status = TripStatus.IN_PROGRESS;
        actualStart = at;
    }

    public void complete(Instant at) {
        if (status != TripStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Trip " + code + " is not in progress");
        }
        status = TripStatus.COMPLETED;
        actualEnd = at;
    }

    public void cancel(Instant at) {
        if (!status.isLive()) {
            throw new BusinessRuleException("Trip " + code + " is already " + status.label().toLowerCase());
        }
        status = TripStatus.CANCELLED;
        actualEnd = at;
    }

    public void reassign(Vehicle newVehicle, Driver newDriver) {
        this.vehicle = newVehicle;
        this.driver = newDriver;
    }

    public void applyRisk(int score, RiskBand band, String reasons, Instant at) {
        this.riskScore = score;
        this.riskBand = band;
        this.riskReasons = reasons;
        this.riskEvaluatedAt = at;
    }

    public void adjustDelay(int minutes) {
        this.delayAdjustMin = minutes;
    }

    public Optional<TripStop> nextOpenStop() {
        return stops.stream().filter(s -> !s.getStatus().isDone()).findFirst();
    }

    public long completedStopCount() {
        return stops.stream().filter(s -> s.getStatus().isDone()).count();
    }

    public boolean allStopsDone() {
        return stops.stream().allMatch(s -> s.getStatus().isDone());
    }

    public int progressPercent() {
        if (stops.isEmpty()) {
            return 0;
        }
        return (int) Math.round(100.0 * completedStopCount() / stops.size());
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public Driver getDriver() {
        return driver;
    }

    public TripStatus getStatus() {
        return status;
    }

    public Instant getPlannedStart() {
        return plannedStart;
    }

    public Instant getPlannedEnd() {
        return plannedEnd;
    }

    public void setPlannedEnd(Instant plannedEnd) {
        this.plannedEnd = plannedEnd;
    }

    public Instant getActualStart() {
        return actualStart;
    }

    public Instant getActualEnd() {
        return actualEnd;
    }

    public int getTotalWeightKg() {
        return totalWeightKg;
    }

    public void setTotalWeightKg(int totalWeightKg) {
        this.totalWeightKg = totalWeightKg;
    }

    public int getDelayAdjustMin() {
        return delayAdjustMin;
    }

    public boolean isAutoCompleteStops() {
        return autoCompleteStops;
    }

    public void setAutoCompleteStops(boolean autoCompleteStops) {
        this.autoCompleteStops = autoCompleteStops;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public RiskBand getRiskBand() {
        return riskBand;
    }

    public String getRiskReasons() {
        return riskReasons;
    }

    public Instant getRiskEvaluatedAt() {
        return riskEvaluatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<TripStop> getStops() {
        return stops;
    }

    /** For the seeder: writes a finished trip with past timestamps. */
    public void restoreHistory(TripStatus status, Instant actualStart, Instant actualEnd, Instant plannedEnd) {
        this.status = status;
        this.actualStart = actualStart;
        this.actualEnd = actualEnd;
        this.plannedEnd = plannedEnd;
    }
}
