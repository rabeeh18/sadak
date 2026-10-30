package com.fleetops.incident;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentActionRepository extends JpaRepository<IncidentAction, Long> {

    List<IncidentAction> findByIncidentIdOrderByPerformedAtAscIdAsc(Long incidentId);
}
