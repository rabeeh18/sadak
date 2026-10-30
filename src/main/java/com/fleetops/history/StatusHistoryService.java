package com.fleetops.history;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.security.CurrentUser;

@Service
public class StatusHistoryService {

    public static final String TRIP = "TRIP";
    public static final String STOP = "STOP";
    public static final String SHIPMENT = "SHIPMENT";

    private final StatusChangeRepository repository;
    private final OperationalClock clock;

    public StatusHistoryService(StatusChangeRepository repository, OperationalClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityType, Long entityId, Enum<?> from, Enum<?> to, String reason) {
        repository.save(new StatusChange(entityType, entityId, from == null ? null : from.name(), to.name(),
            reason, CurrentUser.username(), clock.now()));
    }

    @Transactional(readOnly = true)
    public List<StatusChange> historyOf(String entityType, Long entityId) {
        return repository.findByEntityTypeAndEntityIdOrderByChangedAtAscIdAsc(entityType, entityId);
    }
}
