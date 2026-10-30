package com.fleetops.settings;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.audit.AuditFields;
import com.fleetops.audit.AuditService;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.security.CurrentUser;

/**
 * Typed access to system settings and risk rules. Values are cached in memory because the
 * monitoring loop reads them several times a second; every write goes through here so the
 * cache never goes stale.
 */
@Service
public class SettingsService {

    private final SystemSettingRepository settings;
    private final RiskRuleRepository rules;
    private final AuditService audit;
    private final OperationalClock clock;

    private final Map<String, String> settingCache = new ConcurrentHashMap<>();
    private volatile Map<String, RuleSnapshot> ruleCache = Map.of();

    public SettingsService(SystemSettingRepository settings, RiskRuleRepository rules,
                           AuditService audit, OperationalClock clock) {
        this.settings = settings;
        this.rules = rules;
        this.audit = audit;
        this.clock = clock;
    }

    /** Immutable view of a rule for the risk engine. */
    public record RuleSnapshot(String code, String label, int points, Double threshold, String unit, boolean enabled) {
    }

    @Transactional(readOnly = true)
    public void reload() {
        settingCache.clear();
        settings.findAll().forEach(s -> settingCache.put(s.getKey(), s.getValue()));
        ruleCache = rules.findAll().stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(RiskRule::getCode,
                r -> new RuleSnapshot(r.getCode(), r.getLabel(), r.getPoints(), r.getThreshold(), r.getUnit(), r.isEnabled())));
    }

    public String get(String key) {
        if (settingCache.isEmpty()) {
            reload();
        }
        String value = settingCache.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing system setting " + key);
        }
        return value;
    }

    public int getInt(String key) {
        return Integer.parseInt(get(key).trim());
    }

    public double getDouble(String key) {
        return Double.parseDouble(get(key).trim());
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(get(key).trim());
    }

    public RuleSnapshot rule(String code) {
        if (ruleCache.isEmpty()) {
            reload();
        }
        RuleSnapshot rule = ruleCache.get(code);
        if (rule == null) {
            throw new IllegalStateException("Missing risk rule " + code);
        }
        return rule;
    }

    @Transactional(readOnly = true)
    public List<SystemSetting> allSettings() {
        return settings.findAllByOrderByKeyAsc();
    }

    @Transactional(readOnly = true)
    public List<RiskRule> allRules() {
        return rules.findAllByOrderBySortOrderAsc();
    }

    @Transactional
    public void updateSetting(String key, String value) {
        SystemSetting setting = settings.findById(key).orElseThrow(() -> new NotFoundException("Setting", key));
        String old = setting.getValue();
        if (old.equals(value)) {
            return;
        }
        setting.update(value, CurrentUser.username(), clock.now());
        settingCache.put(key, value);
        audit.record("SETTING_CHANGED", "SETTING", key, setting.getLabel() + ": " + old + " → " + value,
            AuditFields.of("value", old), AuditFields.of("value", value));
    }

    /** System-internal writes (e.g. saving the simulation clock) that are not audited. */
    @Transactional
    public void writeInternal(String key, String value) {
        settings.findById(key).ifPresent(s -> s.update(value, CurrentUser.SYSTEM, Instant.now()));
        settingCache.put(key, value);
    }

    @Transactional
    public void updateRule(String code, int points, Double threshold, boolean enabled) {
        if (points < 0 || points > 100) {
            throw new BusinessRuleException("Points must be between 0 and 100");
        }
        RiskRule rule = rules.findById(code).orElseThrow(() -> new NotFoundException("Risk rule", code));
        if (rule.getPoints() == points && java.util.Objects.equals(rule.getThreshold(), threshold) && rule.isEnabled() == enabled) {
            return;
        }
        Map<String, Object> before = AuditFields.of("points", rule.getPoints(), "threshold", rule.getThreshold(), "enabled", rule.isEnabled());
        rule.update(points, threshold, enabled, CurrentUser.username(), clock.now());
        audit.record("RISK_RULE_CHANGED", "RISK_RULE", code, "Risk rule " + rule.getLabel() + " changed",
            before, AuditFields.of("points", points, "threshold", threshold, "enabled", enabled));
        reload();
    }
}
