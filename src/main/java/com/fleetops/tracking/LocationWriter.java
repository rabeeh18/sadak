package com.fleetops.tracking;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.fleet.VehicleRepository;
import com.fleetops.settings.SettingsService;

/**
 * Batches database writes for tracking: location history rows and each vehicle's last known
 * position. Ingestion only enqueues, so the hot path never waits on the database.
 */
@Component
public class LocationWriter {

    private static final Logger log = LoggerFactory.getLogger(LocationWriter.class);

    private final VehicleLocationRepository locations;
    private final VehicleRepository vehicles;
    private final SettingsService settings;
    private final OperationalClock clock;
    private final ConcurrentLinkedQueue<VehicleLocation> queue = new ConcurrentLinkedQueue<>();
    private LocationIngestionService ingestion;

    public LocationWriter(VehicleLocationRepository locations, VehicleRepository vehicles,
                          SettingsService settings, OperationalClock clock) {
        this.locations = locations;
        this.vehicles = vehicles;
        this.settings = settings;
        this.clock = clock;
    }

    /** Set by ingestion to break the construction cycle; used to read latest positions. */
    void bind(LocationIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    void enqueue(VehicleLocation location) {
        queue.add(location);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    @Transactional
    public void flush() {
        List<VehicleLocation> batch = new ArrayList<>();
        VehicleLocation next;
        while ((next = queue.poll()) != null) {
            batch.add(next);
        }
        if (!batch.isEmpty()) {
            locations.saveAll(batch);
        }
        if (ingestion != null) {
            ingestion.latestFixes().values().forEach(fix -> vehicles.findById(fix.vehicleId())
                .ifPresent(v -> {
                    if (v.getLastSeenAt() == null || fix.at().isAfter(v.getLastSeenAt())) {
                        v.recordPosition(fix.position(), fix.at());
                    }
                }));
        }
    }

    /** Nightly retention: location history older than the configured number of days is deleted. */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void applyRetention() {
        int days = settings.getInt("tracking.retention-days");
        int removed = locations.deleteOlderThan(clock.now().minus(Duration.ofDays(days)));
        if (removed > 0) {
            log.info("Retention removed {} location rows older than {} days", removed, days);
        }
    }
}
