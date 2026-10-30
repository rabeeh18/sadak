package com.fleetops.tracking;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Throttled position history; ids only, so writes stay cheap. */
@Entity
@Table(name = "vehicle_locations")
public class VehicleLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    private double lat;
    private double lng;

    @Column(name = "speed_kph")
    private Float speedKph;

    protected VehicleLocation() {
    }

    public VehicleLocation(Long vehicleId, Long tripId, Instant recordedAt, double lat, double lng, Float speedKph) {
        this.vehicleId = vehicleId;
        this.tripId = tripId;
        this.recordedAt = recordedAt;
        this.lat = lat;
        this.lng = lng;
        this.speedKph = speedKph;
    }

    public Long getId() {
        return id;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public Long getTripId() {
        return tripId;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public Float getSpeedKph() {
        return speedKph;
    }
}
