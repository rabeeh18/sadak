package com.fleetops.web;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fleetops.fleet.DriverRepository;
import com.fleetops.security.AccountService;
import com.fleetops.audit.AuditRepository;
import com.fleetops.audit.AuditService;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.security.Role;
import com.fleetops.security.UserAccount;
import com.fleetops.security.UserAccountRepository;
import com.fleetops.settings.RiskRule;
import com.fleetops.settings.SettingsService;
import com.fleetops.settings.SystemSetting;

/** Admin only (enforced by SecurityConfig for /admin/**). */
@Controller
public class AdminController {

    private final SettingsService settings;
    private final UserAccountRepository users;
    private final AuditRepository audit;
    private final AuditService auditService;
    private final AccountService accounts;
    private final DriverRepository drivers;
    private final com.fleetops.fleet.WarehouseRepository warehouses;
    /** Present in demo mode only. */
    private final org.springframework.beans.factory.ObjectProvider<com.fleetops.demo.DemoReset> demoReset;

    public AdminController(SettingsService settings, UserAccountRepository users, AuditRepository audit,
                           AuditService auditService, AccountService accounts,
                           DriverRepository drivers, com.fleetops.fleet.WarehouseRepository warehouses,
                           org.springframework.beans.factory.ObjectProvider<com.fleetops.demo.DemoReset> demoReset) {
        this.warehouses = warehouses;
        this.demoReset = demoReset;
        this.settings = settings;
        this.users = users;
        this.audit = audit;
        this.auditService = auditService;
        this.accounts = accounts;
        this.drivers = drivers;
    }

    @GetMapping("/admin/settings")
    public String settings(Model model) {
        List<SystemSetting> visible = settings.allSettings().stream().filter(s -> !s.getKey().startsWith("sim.")).toList();
        model.addAttribute("rules", settings.allRules());
        model.addAttribute("settings", visible);
        model.addAttribute("tab", "settings");
        return "admin/settings";
    }

    /** Demo mode: throw everything away and start a fresh demo day. */
    @PostMapping("/admin/demo-reset")
    public String resetDemo(RedirectAttributes ra) {
        com.fleetops.demo.DemoReset reset = demoReset.getIfAvailable();
        if (reset == null) {
            throw new NotFoundException("Page", "/admin/demo-reset");
        }
        reset.reset();
        ra.addFlashAttribute("ok", "Demo data reset: a fresh day with trucks on the road. Accounts made during the demo are gone.");
        return "redirect:/admin/settings";
    }

    @PostMapping("/admin/rules/{code}")
    public String updateRule(@PathVariable String code, @RequestParam int points, @RequestParam(required = false) Double threshold,
                             @RequestParam(defaultValue = "false") boolean enabled, RedirectAttributes ra) {
        RiskRule existing = settings.allRules().stream().filter(r -> r.getCode().equals(code)).findFirst()
            .orElseThrow(() -> new NotFoundException("Risk rule", code));
        if (existing.getThreshold() != null && (threshold == null || threshold < 0)) {
            throw new BusinessRuleException("Give a threshold of zero or more for " + existing.getLabel());
        }
        settings.updateRule(code, points, existing.getThreshold() == null ? null : threshold, enabled);
        ra.addFlashAttribute("ok", existing.getLabel() + " saved. The next monitoring cycle uses it.");
        return "redirect:/admin/settings";
    }

    @PostMapping("/admin/settings/{key}")
    public String updateSetting(@PathVariable String key, @RequestParam String value, RedirectAttributes ra) {
        String v = value.trim();
        if (key.startsWith("tracking.") || key.equals("sla.at-risk-min") || key.equals("risk.late-action-min")) {
            try {
                int n = Integer.parseInt(v);
                if (n < 0 || n > 100_000) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new BusinessRuleException("Enter a whole number for this setting");
            }
        }
        settings.updateSetting(key, v);
        ra.addFlashAttribute("ok", "Setting saved.");
        return "redirect:/admin/settings";
    }

    // ------------------------------------------------------------------ users

    @GetMapping("/admin/users")
    @Transactional(readOnly = true)
    public String users(Model model) {
        model.addAttribute("users", users.findAllByOrderByFullNameAsc());
        model.addAttribute("roles", Role.values());
        model.addAttribute("staffRoles", List.of(Role.DISPATCHER, Role.ADMIN));
        List<com.fleetops.fleet.Driver> allDrivers = drivers.findAllByOrderByFullNameAsc();
        model.addAttribute("unlinkedDrivers", allDrivers.stream().filter(d -> d.getUser() == null).toList());
        model.addAttribute("driverOf", allDrivers.stream().filter(d -> d.getUser() != null)
            .collect(java.util.stream.Collectors.toMap(d -> d.getUser().getId(), com.fleetops.fleet.Driver::getFullName)));
        model.addAttribute("activeAdmins", users.countActiveWithRole(Role.ADMIN));
        model.addAttribute("warehouses", warehouses.findAllByOrderByNameAsc());
        model.addAttribute("warehouseNames", warehouses.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(com.fleetops.fleet.Warehouse::getId, com.fleetops.fleet.Warehouse::getName)));
        model.addAttribute("tab", "users");
        return "admin/users";
    }

    @PostMapping("/admin/users/invite")
    public String invite(@RequestParam String fullName, @RequestParam String email, @RequestParam Role role,
                         @RequestParam(required = false) Long driverId, @RequestParam(required = false) Long warehouseId,
                         RedirectAttributes ra) {
        AccountService.Link link = accounts.invite(fullName, email, role, role == Role.DRIVER ? driverId : null,
            role == Role.WAREHOUSE ? warehouseId : null);
        flashLink(ra, link, "Invite");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/role")
    public String changeRole(@PathVariable long id, @RequestParam Role role, RedirectAttributes ra) {
        accounts.changeRole(id, role);
        ra.addFlashAttribute("ok", "Role updated to " + role.label() + ".");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/toggle")
    public String toggle(@PathVariable long id, RedirectAttributes ra) {
        UserAccount u = accounts.toggleEnabled(id);
        ra.addFlashAttribute("ok", u.getFullName() + (u.isEnabled() ? " can sign in again." : " can no longer sign in."));
        return "redirect:/admin/users";
    }

    /** A fresh invite for pending accounts, or a password reset link for active ones. */
    @PostMapping("/admin/users/{id}/link")
    public String link(@PathVariable long id, RedirectAttributes ra) {
        boolean pending = users.findById(id).map(UserAccount::isInvitePending).orElse(false);
        flashLink(ra, accounts.resetLink(id), pending ? "Invite" : "Password reset link");
        return "redirect:/admin/users";
    }

    private void flashLink(RedirectAttributes ra, AccountService.Link link, String what) {
        ra.addFlashAttribute("shareNote", what + " created for " + link.email()
            + ". Send them this link yourself, for example on WhatsApp. It works once.");
        ra.addFlashAttribute("shareLink", link.url());
    }

    // ------------------------------------------------------------------ audit

    @GetMapping("/admin/audit")
    public String audit(@RequestParam(required = false) String actor, @RequestParam(required = false) String entityType,
                        @RequestParam(required = false) String entityId, @RequestParam(defaultValue = "0") int page, Model model) {
        String a = blank(actor);
        String t = blank(entityType);
        String id = blank(entityId);
        model.addAttribute("page", audit.search(a, t, id, PageRequest.of(Math.max(0, page), 50)));
        model.addAttribute("actors", audit.distinctActors());
        model.addAttribute("types", audit.distinctEntityTypes());
        model.addAttribute("actor", a);
        model.addAttribute("entityType", t);
        model.addAttribute("entityId", id);
        model.addAttribute("tab", "audit");
        model.addAttribute("links", Map.of("TRIP", "/trips/", "INCIDENT", "/incidents/", "VEHICLE", "/fleet/vehicles/",
            "DRIVER", "/fleet/drivers/", "SHIPMENT", "/shipments/"));
        return "admin/audit";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
