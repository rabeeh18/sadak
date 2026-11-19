package com.fleetops.demo;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fleetops.audit.AuditService;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.time.StartupGate;
import com.fleetops.risk.MonitoringService;
import com.fleetops.simulation.SimulationEngine;
import com.fleetops.tracking.LiveFleet;
import com.fleetops.tracking.LocationIngestionService;
import com.fleetops.tracking.LocationWriter;

/**
 * Demo mode only: throws away everything that happened and seeds a fresh demo day, while the
 * app keeps running. The background jobs are held, the in-memory picture is forgotten, every
 * data table is emptied, and the normal start-up seeding runs again. Risk rules and settings
 * stay as an admin left them.
 */
@Service
@Profile("demo")
public class DemoReset {

    private static final Logger log = LoggerFactory.getLogger(DemoReset.class);

    /** Configuration that survives a reset. */
    private static final List<String> KEEP = List.of("flyway_schema_history", "roles", "risk_rules", "system_settings");

    private final JdbcTemplate jdbc;
    private final StartupGate gate;
    private final LiveFleet live;
    private final LocationIngestionService ingestion;
    private final MonitoringService monitoring;
    private final SimulationEngine simulation;
    private final DemoOperations operations;
    private final DemoSeeder seeder;
    private final DemoDesk desk;
    private final OperationalClock clock;
    private final AuditService audit;
    private final LocationWriter writer;
    private final TransactionTemplate tx;

    public DemoReset(JdbcTemplate jdbc, StartupGate gate, LiveFleet live, LocationIngestionService ingestion,
                     MonitoringService monitoring, SimulationEngine simulation, DemoOperations operations,
                     DemoSeeder seeder, DemoDesk desk, OperationalClock clock, AuditService audit,
                     LocationWriter writer, PlatformTransactionManager txManager) {
        this.writer = writer;
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.gate = gate;
        this.live = live;
        this.ingestion = ingestion;
        this.monitoring = monitoring;
        this.simulation = simulation;
        this.operations = operations;
        this.seeder = seeder;
        this.desk = desk;
        this.clock = clock;
        this.audit = audit;
    }

    public synchronized void reset() {
        long started = System.currentTimeMillis();
        gate.close();
        try {
            // Let a simulation or monitoring step that already started finish before the data goes.
            Thread.sleep(1500);
            simulation.forgetAll();
            monitoring.forgetAll();
            ingestion.forgetAll();
            writer.forgetAll();
            live.forgetAll();
            operations.forgetAll();

            List<String> tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'public'", String.class).stream()
                .filter(t -> !KEEP.contains(t)).toList();
            jdbc.execute("truncate table " + String.join(", ", tables) + " restart identity cascade");
            // Codes (TR-, INC-, SR-, tracking numbers) start again from their first value.
            jdbc.queryForList("select sequencename from pg_sequences where schemaname = 'public' and sequencename not like '%id_seq'",
                String.class).forEach(seq -> jdbc.execute("alter sequence " + seq + " restart"));

            clock.restore(Instant.now(), clock.speed(), false);
            seeder.seed();
            desk.setUp();
            live.loadAll();
            tx.executeWithoutResult(s -> audit.record("DEMO_RESET", "SYSTEM", "demo", "Demo data reset to a fresh day"));
            log.info("Demo reset in {} s", (System.currentTimeMillis() - started) / 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo reset was interrupted", e);
        } finally {
            gate.reopen();
        }
    }
}
