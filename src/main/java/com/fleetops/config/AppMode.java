package com.fleetops.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Demo or production. Demo (the default profile) seeds synthetic Punjab data, runs the simulator and
 * lets the clock run fast. Production ({@code prod}) starts empty, runs on real time and has no shortcuts.
 */
@Component
public class AppMode {

    private final boolean demo;

    public AppMode(Environment env) {
        this.demo = env.acceptsProfiles(Profiles.of("demo"));
    }

    public boolean demo() {
        return demo;
    }
}
