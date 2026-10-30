package com.fleetops.history;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusChangeRepository extends JpaRepository<StatusChange, Long> {

    List<StatusChange> findByEntityTypeAndEntityIdOrderByChangedAtAscIdAsc(String entityType, Long entityId);
}
