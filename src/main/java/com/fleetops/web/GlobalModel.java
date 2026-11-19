package com.fleetops.web;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.complaint.Complaint;
import com.fleetops.complaint.ComplaintRepository;
import com.fleetops.config.AppMode;
import com.fleetops.fleet.Warehouse;
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.incident.IncidentRepository;
import com.fleetops.security.Role;
import com.fleetops.security.UserAccountRepository;

/** Values every page header needs: who is signed in, the clock, and the open incident count. */
@ControllerAdvice(basePackages = "com.fleetops.web")
public class GlobalModel {

    private final UserAccountRepository users;
    private final IncidentRepository incidents;
    private final OperationalClock clock;
    private final AppMode mode;
    private final WarehouseRepository warehouses;
    private final ComplaintRepository complaints;

    /** For pages seen by someone without an account record: every flag the header reads is false. */
    private static final Map<String, Object> NOBODY = Map.of("username", "", "name", "", "role", "",
        "dispatcher", false, "warehouse", false, "warehouseName", "");

    public GlobalModel(UserAccountRepository users, IncidentRepository incidents, OperationalClock clock, AppMode mode,
                       WarehouseRepository warehouses, ComplaintRepository complaints) {
        this.warehouses = warehouses;
        this.complaints = complaints;
        this.users = users;
        this.incidents = incidents;
        this.clock = clock;
        this.mode = mode;
    }

    /** True in demo mode: synthetic data, simulator controls and quick sign-in are shown. */
    @ModelAttribute("demoMode")
    public boolean demoMode() {
        return mode.demo();
    }

    @ModelAttribute("me")
    public Map<String, Object> me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return NOBODY;
        }
        return users.findByUsername(auth.getName()).<Map<String, Object>>map(u -> {
            boolean dispatcher = u.hasRole(Role.DISPATCHER);
            boolean desk = !dispatcher && u.hasRole(Role.WAREHOUSE);
            String warehouseName = desk && u.getWarehouseId() != null
                ? warehouses.findById(u.getWarehouseId()).map(Warehouse::getName).orElse("") : "";
            return Map.of(
                "username", u.getUsername(),
                "name", u.getFullName(),
                "role", u.primaryRole().label(),
                "dispatcher", dispatcher,
                "warehouse", desk,
                "warehouseName", warehouseName);
        }).orElse(NOBODY);
    }

    @ModelAttribute("clockState")
    public Map<String, Object> clockState() {
        return Map.of("now", clock.now().toEpochMilli(), "speed", clock.speed(), "paused", clock.paused());
    }

    /** Open customer complaints across all warehouses, for the control room. */
    @ModelAttribute("openComplaintCount")
    public long openComplaintCount(Authentication auth) {
        return auth == null ? 0 : complaints.countByStatusNot(Complaint.Status.RESOLVED);
    }

    @ModelAttribute("openIncidentCount")
    public long openIncidentCount(Authentication auth) {
        return auth == null ? 0 : incidents.countOpen();
    }
}
