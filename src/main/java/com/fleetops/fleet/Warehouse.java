package com.fleetops.fleet;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.fleetops.common.geo.GeoPoint;

@Entity
@Table(name = "warehouses")
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String address;

    private double lat;
    private double lng;
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Warehouse() {
    }

    public Warehouse(String code, String name, String city, String address, double lat, double lng) {
        this.code = code;
        this.name = name;
        this.city = city;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }

    public String getAddress() {
        return address;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public boolean isActive() {
        return active;
    }

    public GeoPoint location() {
        return new GeoPoint(lat, lng);
    }

    public void update(String name, String city, String address, double lat, double lng, boolean active) {
        this.name = name;
        this.city = city;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.active = active;
    }
}
