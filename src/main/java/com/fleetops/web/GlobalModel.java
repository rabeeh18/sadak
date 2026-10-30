package com.fleetops.web;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.incident.IncidentRepository;
import com.fleetops.security.Role;
import com.fleetops.security.UserAccountRepository;

/** Values every page header needs: who is signed in, the clock, and the open incident count. */
@ControllerAdvice(basePackages = "com.fleetops.web")
public class GlobalModel {

    private final UserAccountRepository users;
    private final IncidentRepository incidents;
    private final OperationalClock clock;

    public GlobalModel(UserAccountRepository users, IncidentRepository incidents, OperationalClock clock) {
        this.users = users;
        this.incidents = incidents;
        this.clock = clock;
    }

    @ModelAttribute("me")
    public Map<String, Object> me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return Map.of();
        }
        return users.findByUsername(auth.getName()).<Map<String, Object>>map(u -> Map.of(
            "username", u.getUsername(),
            "name", u.getFullName(),
            "role", u.hasRole(Role.ADMIN) ? "Admin" : u.hasRole(Role.DISPATCHER) ? "Dispatcher" : "Driver",
            "admin", u.hasRole(Role.ADMIN),
            "dispatcher", u.hasRole(Role.DISPATCHER) || u.hasRole(Role.ADMIN))).orElse(Map.of());
    }

    @ModelAttribute("clockState")
    public Map<String, Object> clockState() {
        return Map.of("now", clock.now().toEpochMilli(), "speed", clock.speed(), "paused", clock.paused());
    }

    @ModelAttribute("openIncidentCount")
    public long openIncidentCount(Authentication auth) {
        return auth == null ? 0 : incidents.countOpen();
    }
}
