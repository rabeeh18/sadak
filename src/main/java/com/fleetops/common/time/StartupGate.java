package com.fleetops.common.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Scheduled jobs start as soon as the context refreshes, before the clock is restored and demo
 * data is seeded. They check this gate and skip their run until start-up has finished.
 * Tests switch the live loops off entirely with {@code fleet.live-loops=false}.
 */
@Component
public class StartupGate {

    private final boolean enabled;
    private volatile boolean open;

    public StartupGate(@Value("${fleet.live-loops:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void openGate() {
        open = enabled;
    }

    public boolean isOpen() {
        return open;
    }
}
