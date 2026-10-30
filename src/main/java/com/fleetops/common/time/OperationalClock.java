package com.fleetops.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

/**
 * The single source of "now" for every operational decision: ETAs, deadlines, timestamps.
 * <p>
 * In simulation it runs faster than wall-clock time. Simulated time is anchored at a point
 * (simulated instant + real nanoTime) and advances by {@code speed} simulated seconds per real
 * second. Changing speed or pausing re-anchors so time never jumps backwards.
 */
@Component
public class OperationalClock {

    public static final double MIN_SPEED = 1;
    public static final double MAX_SPEED = 120;

    private Instant anchorSim = Instant.now();
    private long anchorRealNanos = System.nanoTime();
    private double speed = 1;
    private boolean paused = false;

    public synchronized Instant now() {
        if (paused) {
            return anchorSim;
        }
        long elapsedRealNanos = System.nanoTime() - anchorRealNanos;
        long simNanos = (long) (elapsedRealNanos * speed);
        return anchorSim.plusNanos(simNanos);
    }

    public synchronized double speed() {
        return speed;
    }

    public synchronized boolean paused() {
        return paused;
    }

    public synchronized void setSpeed(double newSpeed) {
        double clamped = Math.max(MIN_SPEED, Math.min(MAX_SPEED, newSpeed));
        rebase();
        this.speed = clamped;
    }

    public synchronized void pause() {
        rebase();
        this.paused = true;
    }

    public synchronized void resume() {
        rebase();
        this.paused = false;
    }

    /** Restores a previously saved simulated time; never moves the clock behind real time. */
    public synchronized void restore(Instant savedSimNow, double savedSpeed, boolean savedPaused) {
        Instant realNow = Instant.now();
        this.anchorSim = savedSimNow != null && savedSimNow.isAfter(realNow) ? savedSimNow : realNow;
        this.anchorRealNanos = System.nanoTime();
        this.speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, savedSpeed));
        this.paused = savedPaused;
    }

    /** Jumps simulated time forward, e.g. to skip a quiet night in a demo. */
    public synchronized void advance(Duration by) {
        rebase();
        this.anchorSim = anchorSim.plus(by);
    }

    /** A java.time view of this clock, for APIs that take a {@link Clock}. */
    public Clock asClock() {
        OperationalClock self = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return self.now();
            }
        };
    }

    private void rebase() {
        this.anchorSim = now();
        this.anchorRealNanos = System.nanoTime();
    }
}
