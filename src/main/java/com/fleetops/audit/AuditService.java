package com.fleetops.audit;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.security.CurrentUser;

import tools.jackson.databind.ObjectMapper;

/**
 * Records who changed what. Runs inside the caller's transaction so an audit row exists
 * exactly when the change it describes was committed.
 */
@Service
public class AuditService {

    private final AuditRepository repository;
    private final OperationalClock clock;
    private final ObjectMapper json;

    public AuditService(AuditRepository repository, OperationalClock clock, ObjectMapper json) {
        this.repository = repository;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Object entityId, String summary,
                       Map<String, ?> before, Map<String, ?> after) {
        repository.save(new AuditEntry(
            CurrentUser.username(), action, entityType,
            entityId == null ? null : String.valueOf(entityId),
            truncate(summary, 300), toJson(before), toJson(after), clock.now()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Object entityId, String summary) {
        record(action, entityType, entityId, summary, null, null);
    }

    private String toJson(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return json.writeValueAsString(values);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
