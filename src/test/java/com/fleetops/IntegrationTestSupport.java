package com.fleetops;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.DriverRepository;
import com.fleetops.fleet.Vehicle;
import com.fleetops.fleet.VehicleRepository;
import com.fleetops.fleet.Warehouse;
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.security.Role;
import com.fleetops.security.UserAccount;
import com.fleetops.security.UserAccountRepository;
import com.fleetops.settings.SettingsService;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;

/**
 * Base for tests that run against the real PostgreSQL schema in the fleet_test database.
 * Each test starts from empty operational tables; rules and settings from the migrations stay.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected WarehouseRepository warehouses;
    @Autowired protected VehicleRepository vehicles;
    @Autowired protected DriverRepository drivers;
    @Autowired protected ShipmentRepository shipments;
    @Autowired protected UserAccountRepository users;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected OperationalClock clock;
    @Autowired protected SettingsService settings;

    private int counter;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("""
            truncate table incident_actions, incidents, notifications, audit_log, status_history, risk_evaluations,
                sla_breach_events, vehicle_locations, delivery_proofs, shipments, trip_assignments, route_versions,
                trip_stops, trips, maintenance_records, drivers, vehicles, warehouses, user_roles, users restart identity cascade""");
        settings.reload();
        clock.restore(Instant.now(), 1, true);
    }

    protected Warehouse warehouse() {
        return warehouses.save(new Warehouse("LDH", "Ludhiana DC", "Ludhiana", "Focal Point", 30.8786, 75.9351));
    }

    protected Vehicle vehicle(Warehouse w, int capacityKg) {
        counter++;
        return vehicles.save(new Vehicle("PB 10 GX " + String.format("%04d", 1000 + counter), "Tata 407", capacityKg, w));
    }

    protected Driver driver(Warehouse w, String name) {
        counter++;
        return drivers.save(new Driver(name, "+91 98000000" + String.format("%02d", counter), "PB10 2020 " + (1000000 + counter),
            LocalDate.now().plusYears(2), w));
    }

    protected Shipment shipment(Warehouse w, double lat, double lng, int weight) {
        counter++;
        return shipments.save(new Shipment("FLT" + counter, w, "Recipient", null, "Moga — Test Traders", "Main Bazaar, Moga",
            "142001", lat, lng, weight, clock.now().plus(Duration.ofHours(6)), clock.now()));
    }

    protected UserAccount account(String username, Role role) {
        return users.save(new UserAccount(username, encoder.encode("test-password-123"), username, EnumSet.of(role)));
    }
}
