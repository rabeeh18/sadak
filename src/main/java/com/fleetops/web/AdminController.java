package com.fleetops.web;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fleetops.audit.AuditFields;
import com.fleetops.audit.AuditRepository;
import com.fleetops.audit.AuditService;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.security.CurrentUser;
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
    private final PasswordEncoder encoder;

    public AdminController(SettingsService settings, UserAccountRepository users, AuditRepository audit,
                           AuditService auditService, PasswordEncoder encoder) {
        this.settings = settings;
        this.users = users;
        this.audit = audit;
        this.auditService = auditService;
        this.encoder = encoder;
    }

    @GetMapping("/admin/settings")
    public String settings(Model model) {
        List<SystemSetting> visible = settings.allSettings().stream().filter(s -> !s.getKey().startsWith("sim.")).toList();
        model.addAttribute("rules", settings.allRules());
        model.addAttribute("settings", visible);
        model.addAttribute("tab", "settings");
        return "admin/settings";
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
        if (key.startsWith("risk.band.") || key.startsWith("tracking.") || key.equals("sla.at-risk-min")) {
            try {
                int n = Integer.parseInt(v);
                if (n < 0 || n > 100_000) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new BusinessRuleException("Enter a whole number for this setting");
            }
        }
        if (key.equals("incident.sla-min-band") && !List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(v.toUpperCase())) {
            throw new BusinessRuleException("Use LOW, MEDIUM, HIGH or CRITICAL");
        }
        settings.updateSetting(key, key.equals("incident.sla-min-band") ? v.toUpperCase() : v);
        ra.addFlashAttribute("ok", "Setting saved.");
        return "redirect:/admin/settings";
    }

    // ------------------------------------------------------------------ users

    @GetMapping("/admin/users")
    public String users(Model model) {
        model.addAttribute("users", users.findAllByOrderByUsernameAsc());
        model.addAttribute("roles", Role.values());
        model.addAttribute("tab", "users");
        return "admin/users";
    }

    @PostMapping("/admin/users")
    @Transactional
    public String createUser(@RequestParam String username, @RequestParam String fullName, @RequestParam String password,
                             @RequestParam Role role, RedirectAttributes ra) {
        String name = username.trim().toLowerCase();
        if (!name.matches("[a-z0-9._-]{3,40}")) {
            throw new BusinessRuleException("Usernames are 3–40 lowercase letters, digits, dots, dashes or underscores");
        }
        if (users.existsByUsername(name)) {
            throw new BusinessRuleException("The username " + name + " is taken");
        }
        if (password.length() < 10) {
            throw new BusinessRuleException("Passwords need at least 10 characters");
        }
        UserAccount u = users.save(new UserAccount(name, encoder.encode(password), fullName.trim(), EnumSet.of(role)));
        auditService.record("USER_CREATED", "USER", u.getId(), "User " + name + " created as " + role.label(),
            null, AuditFields.of("role", role));
        ra.addFlashAttribute("ok", "User " + name + " created.");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/toggle")
    @Transactional
    public String toggle(@PathVariable long id, RedirectAttributes ra) {
        UserAccount u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        if (u.getUsername().equals(CurrentUser.username())) {
            throw new BusinessRuleException("You cannot disable your own account");
        }
        u.setEnabled(!u.isEnabled());
        auditService.record(u.isEnabled() ? "USER_ENABLED" : "USER_DISABLED", "USER", id,
            "User " + u.getUsername() + (u.isEnabled() ? " enabled" : " disabled"),
            AuditFields.of("enabled", !u.isEnabled()), AuditFields.of("enabled", u.isEnabled()));
        ra.addFlashAttribute("ok", u.getUsername() + (u.isEnabled() ? " can sign in again." : " can no longer sign in."));
        return "redirect:/admin/users";
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
