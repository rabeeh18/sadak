package com.fleetops.audit;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {

    @Query("""
        select a from AuditEntry a
        where (:actor is null or a.actor = :actor)
          and (:entityType is null or a.entityType = :entityType)
          and (:entityId is null or a.entityId = :entityId)
        order by a.occurredAt desc, a.id desc
        """)
    Page<AuditEntry> search(String actor, String entityType, String entityId, Pageable pageable);

    List<AuditEntry> findTop20ByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, String entityId);

    @Query("select distinct a.actor from AuditEntry a order by a.actor")
    List<String> distinctActors();

    @Query("select distinct a.entityType from AuditEntry a order by a.entityType")
    List<String> distinctEntityTypes();
}
