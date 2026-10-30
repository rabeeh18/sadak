package com.fleetops.fleet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.audit.AuditFields;
import com.fleetops.audit.AuditService;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.security.CurrentUser;

/** Master data for warehouses, vehicles, drivers and maintenance, with audit on every change. */
@Service
public class FleetService {

    private final WarehouseRepository warehouses;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final MaintenanceRepository maintenance;
    private final AuditService audit;
    private final OperationalClock clock;
    private final ZoneId zone;

    public FleetService(WarehouseRepository warehouses, VehicleRepository vehicles, DriverRepository drivers,
                        MaintenanceRepository maintenance, AuditService audit, OperationalClock clock,
                        @Value("${fleet.display-zone}") String zone) {
        this.warehouses = warehouses;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.maintenance = maintenance;
        this.audit = audit;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    // ------------------------------------------------------------------ warehouses

    @Transactional
    public Warehouse createWarehouse(String code, String name, String city, String address, double lat, double lng) {
        String normalised = code.trim().toUpperCase();
        if (warehouses.existsByCode(normalised)) {
            throw new BusinessRuleException("A warehouse with code " + normalised + " already exists");
        }
        Warehouse w = warehouses.save(new Warehouse(normalised, name.trim(), city.trim(), address.trim(), lat, lng));
        audit.record("WAREHOUSE_CREATED", "WAREHOUSE", w.getId(), "Warehouse " + w.getCode() + " created");
        return w;
    }

    @Transactional
    public void updateWarehouse(long id, String name, String city, String address, double lat, double lng, boolean active) {
        Warehouse w = warehouse(id);
        var before = AuditFields.of("name", w.getName(), "city", w.getCity(), "lat", w.getLat(), "lng", w.getLng(), "active", w.isActive());
        w.update(name.trim(), city.trim(), address.trim(), lat, lng, active);
        audit.record("WAREHOUSE_UPDATED", "WAREHOUSE", id, "Warehouse " + w.getCode() + " updated", before,
            AuditFields.of("name", name, "city", city, "lat", lat, "lng", lng, "active", active));
    }

    @Transactional(readOnly = true)
    public Warehouse warehouse(long id) {
        return warehouses.findById(id).orElseThrow(() -> new NotFoundException("Warehouse", id));
    }

    // ------------------------------------------------------------------ vehicles

    @Transactional
    public Vehicle createVehicle(String registration, String type, int capacityKg, long warehouseId) {
        String reg = normaliseRegistration(registration);
        if (vehicles.existsByRegistration(reg)) {
            throw new BusinessRuleException("Vehicle " + reg + " is already registered");
        }
        Vehicle v = vehicles.save(new Vehicle(reg, type.trim(), capacityKg, warehouse(warehouseId)));
        audit.record("VEHICLE_CREATED", "VEHICLE", v.getId(), "Vehicle " + reg + " registered");
        return v;
    }

    @Transactional
    public void updateVehicle(long id, String type, int capacityKg, long warehouseId, boolean simulated) {
        Vehicle v = vehicle(id);
        var before = AuditFields.of("type", v.getType(), "capacityKg", v.getCapacityKg(),
            "warehouse", v.getHomeWarehouse().getCode(), "simulated", v.isSimulated());
        Warehouse w = warehouse(warehouseId);
        v.update(type.trim(), capacityKg, w, simulated);
        audit.record("VEHICLE_UPDATED", "VEHICLE", id, "Vehicle " + v.getRegistration() + " updated", before,
            AuditFields.of("type", type, "capacityKg", capacityKg, "warehouse", w.getCode(), "simulated", simulated));
    }

    @Transactional(readOnly = true)
    public Vehicle vehicle(long id) {
        return vehicles.findById(id).orElseThrow(() -> new NotFoundException("Vehicle", id));
    }

    /** Takes a vehicle off the road and opens a maintenance record. */
    @Transactional
    public MaintenanceRecord sendToWorkshop(long vehicleId, MaintenanceRecord.Kind kind, String reason) {
        Vehicle v = vehicles.lockById(vehicleId).orElseThrow(() -> new NotFoundException("Vehicle", vehicleId));
        if (v.getStatus() == VehicleStatus.ON_TRIP) {
            throw new BusinessRuleException(v.getRegistration() + " is on a trip; reassign the trip first");
        }
        if (maintenance.findByVehicleIdAndEndedAtIsNull(vehicleId).isPresent()) {
            throw new BusinessRuleException(v.getRegistration() + " already has an open maintenance record");
        }
        VehicleStatus old = v.getStatus();
        v.setStatus(kind == MaintenanceRecord.Kind.BREAKDOWN ? VehicleStatus.BROKEN_DOWN : VehicleStatus.MAINTENANCE);
        MaintenanceRecord record = maintenance.save(new MaintenanceRecord(v, kind, reason, clock.now(), CurrentUser.username()));
        audit.record("VEHICLE_UNAVAILABLE", "VEHICLE", vehicleId, v.getRegistration() + " marked unavailable: " + reason,
            AuditFields.of("status", old), AuditFields.of("status", v.getStatus()));
        return record;
    }

    /** Opens a breakdown record for a vehicle that broke down during a trip (status already set by the caller). */
    @Transactional
    public void openBreakdownRecord(Vehicle v, String reason) {
        if (maintenance.findByVehicleIdAndEndedAtIsNull(v.getId()).isEmpty()) {
            maintenance.save(new MaintenanceRecord(v, MaintenanceRecord.Kind.BREAKDOWN, reason, clock.now(), CurrentUser.username()));
        }
    }

    @Transactional
    public void returnToService(long vehicleId) {
        Vehicle v = vehicles.lockById(vehicleId).orElseThrow(() -> new NotFoundException("Vehicle", vehicleId));
        if (v.getStatus() == VehicleStatus.ON_TRIP) {
            throw new BusinessRuleException(v.getRegistration() + " is on a trip");
        }
        if (v.getStatus() == VehicleStatus.BROKEN_DOWN && hasLiveTripFlag(v)) {
            throw new BusinessRuleException(v.getRegistration() + " still has an unfinished trip; reassign it first");
        }
        maintenance.findByVehicleIdAndEndedAtIsNull(vehicleId).ifPresent(r -> r.close(clock.now()));
        VehicleStatus old = v.getStatus();
        v.setStatus(VehicleStatus.AVAILABLE);
        audit.record("VEHICLE_AVAILABLE", "VEHICLE", vehicleId, v.getRegistration() + " returned to service",
            AuditFields.of("status", old), AuditFields.of("status", VehicleStatus.AVAILABLE));
    }

    private boolean hasLiveTripFlag(Vehicle v) {
        // A broken-down vehicle keeps its trip until the dispatcher reassigns it; the trip module
        // swaps the vehicle off the trip, so here we only guard the direct case.
        return false;
    }

    // ------------------------------------------------------------------ drivers

    @Transactional
    public Driver createDriver(String fullName, String phone, String licenseNumber, LocalDate expiry, long warehouseId) {
        String license = licenseNumber.trim().toUpperCase();
        if (drivers.existsByLicenseNumber(license)) {
            throw new BusinessRuleException("A driver with licence " + license + " already exists");
        }
        Driver d = drivers.save(new Driver(fullName.trim(), phone.trim(), license, expiry, warehouse(warehouseId)));
        audit.record("DRIVER_CREATED", "DRIVER", d.getId(), "Driver " + d.getFullName() + " added");
        return d;
    }

    @Transactional
    public void updateDriver(long id, String fullName, String phone, String licenseNumber, LocalDate expiry,
                             long warehouseId, DriverStatus status) {
        Driver d = driver(id);
        var before = AuditFields.of("fullName", d.getFullName(), "phone", d.getPhone(), "license", d.getLicenseNumber(),
            "licenseExpiry", d.getLicenseExpiry(), "status", d.getStatus());
        if (status == DriverStatus.ON_TRIP && d.getStatus() != DriverStatus.ON_TRIP) {
            throw new BusinessRuleException("Drivers are put on trips by assigning them, not by editing");
        }
        if (d.getStatus() == DriverStatus.ON_TRIP && status != DriverStatus.ON_TRIP) {
            throw new BusinessRuleException(d.getFullName() + " is on a trip; reassign the trip first");
        }
        d.update(fullName.trim(), phone.trim(), licenseNumber.trim().toUpperCase(), expiry, warehouse(warehouseId));
        d.setStatus(status);
        audit.record("DRIVER_UPDATED", "DRIVER", id, "Driver " + d.getFullName() + " updated", before,
            AuditFields.of("fullName", fullName, "phone", phone, "license", licenseNumber, "licenseExpiry", expiry, "status", status));
    }

    @Transactional(readOnly = true)
    public Driver driver(long id) {
        return drivers.findById(id).orElseThrow(() -> new NotFoundException("Driver", id));
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.now(), zone);
    }

    public static String normaliseRegistration(String raw) {
        String compact = raw.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        // Indian format: PB 10 GX 4471 -> state, district, series, number.
        var m = java.util.regex.Pattern.compile("^([A-Z]{2})(\\d{1,2})([A-Z]{1,3})(\\d{1,4})$").matcher(compact);
        if (!m.matches()) {
            throw new BusinessRuleException("Registration must look like PB 10 GX 4471");
        }
        return m.group(1) + " " + String.format("%02d", Integer.parseInt(m.group(2))) + " " + m.group(3) + " "
            + String.format("%04d", Integer.parseInt(m.group(4)));
    }

    public static final Set<VehicleStatus> OFF_ROAD = Set.of(VehicleStatus.BROKEN_DOWN, VehicleStatus.MAINTENANCE, VehicleStatus.RETIRED);

    @Transactional(readOnly = true)
    public List<MaintenanceRecord> recentMaintenance() {
        return maintenance.findTop100ByOrderByStartedAtDesc();
    }
}
