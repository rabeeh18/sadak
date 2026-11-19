package com.fleetops.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Sadak always runs as the demo (the default profile): synthetic Punjab data, the simulator and a
 * clock that can run fast. Only the automated tests run without it, on real time and with no seeding.
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
