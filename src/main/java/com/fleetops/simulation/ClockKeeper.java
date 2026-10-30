package com.fleetops.simulation;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.settings.SettingsService;

/** Restores the simulation clock on start-up and saves it periodically, so simulated time survives restarts. */
@Component
public class ClockKeeper {

    private static final Logger log = LoggerFactory.getLogger(ClockKeeper.class);

    private final OperationalClock clock;
    private final SettingsService settings;
    private volatile boolean restored;

    public ClockKeeper(OperationalClock clock, SettingsService settings) {
        this.clock = clock;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void restore() {
        settings.reload();
        String saved = settings.get("sim.clock");
        Instant savedAt = saved.isBlank() ? null : Instant.parse(saved);
        clock.restore(savedAt, settings.getDouble("sim.speed"), settings.getBoolean("sim.paused"));
        restored = true;
        log.info("Operational clock at {} (speed {}x{})", clock.now(), clock.speed(), clock.paused() ? ", paused" : "");
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void save() {
        if (restored) {
            settings.writeInternal("sim.clock", clock.now().toString());
        }
    }

    public void saveControls() {
        settings.writeInternal("sim.speed", String.valueOf(clock.speed()));
        settings.writeInternal("sim.paused", String.valueOf(clock.paused()));
        save();
    }
}
