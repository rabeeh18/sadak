package com.fleetops.fleet;

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

import com.fleetops.common.geo.GeoPoint;

@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String registration;

    @Column(nullable = false)
    private String type;

    @Column(name = "capacity_kg")
    private int capacityKg;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_warehouse_id")
    private Warehouse homeWarehouse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleStatus status = VehicleStatus.AVAILABLE;

    @Column(name = "last_lat")
    private Double lastLat;

    @Column(name = "last_lng")
    private Double lastLng;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    private boolean simulated = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Vehicle() {
    }

    public Vehicle(String registration, String type, int capacityKg, Warehouse homeWarehouse) {
        this.registration = registration;
        this.type = type;
        this.capacityKg = capacityKg;
        this.homeWarehouse = homeWarehouse;
        this.lastLat = homeWarehouse.getLat();
        this.lastLng = homeWarehouse.getLng();
    }

    public Long getId() {
        return id;
    }

    public String getRegistration() {
        return registration;
    }

    public String getType() {
        return type;
    }

    public int getCapacityKg() {
        return capacityKg;
    }

    public Warehouse getHomeWarehouse() {
        return homeWarehouse;
    }

    public VehicleStatus getStatus() {
        return status;
    }

    public void setStatus(VehicleStatus status) {
        this.status = status;
    }

    public Double getLastLat() {
        return lastLat;
    }

    public Double getLastLng() {
        return lastLng;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public GeoPoint lastPosition() {
        return lastLat == null ? homeWarehouse.location() : new GeoPoint(lastLat, lastLng);
    }

    public void recordPosition(GeoPoint p, Instant at) {
        this.lastLat = p.lat();
        this.lastLng = p.lng();
        this.lastSeenAt = at;
    }

    public void update(String type, int capacityKg, Warehouse homeWarehouse, boolean simulated) {
        this.type = type;
        this.capacityKg = capacityKg;
        this.homeWarehouse = homeWarehouse;
        this.simulated = simulated;
    }
}
