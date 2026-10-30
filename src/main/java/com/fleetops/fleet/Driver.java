package com.fleetops.fleet;

import java.time.Instant;
import java.time.LocalDate;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import com.fleetops.security.UserAccount;

@Entity
@Table(name = "drivers")
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String phone;

    @Column(name = "license_number", nullable = false, unique = true)
    private String licenseNumber;

    @Column(name = "license_expiry", nullable = false)
    private LocalDate licenseExpiry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_warehouse_id")
    private Warehouse homeWarehouse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DriverStatus status = DriverStatus.AVAILABLE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Driver() {
    }

    public Driver(String fullName, String phone, String licenseNumber, LocalDate licenseExpiry, Warehouse homeWarehouse) {
        this.fullName = fullName;
        this.phone = phone;
        this.licenseNumber = licenseNumber;
        this.licenseExpiry = licenseExpiry;
        this.homeWarehouse = homeWarehouse;
    }

    public Long getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public void setUser(UserAccount user) {
        this.user = user;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public String getLicenseNumber() {
        return licenseNumber;
    }

    public LocalDate getLicenseExpiry() {
        return licenseExpiry;
    }

    public Warehouse getHomeWarehouse() {
        return homeWarehouse;
    }

    public DriverStatus getStatus() {
        return status;
    }

    public void setStatus(DriverStatus status) {
        this.status = status;
    }

    public boolean licenseValidOn(LocalDate date) {
        return !licenseExpiry.isBefore(date);
    }

    public void update(String fullName, String phone, String licenseNumber, LocalDate licenseExpiry, Warehouse homeWarehouse) {
        this.fullName = fullName;
        this.phone = phone;
        this.licenseNumber = licenseNumber;
        this.licenseExpiry = licenseExpiry;
        this.homeWarehouse = homeWarehouse;
    }
}
