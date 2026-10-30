package com.fleetops.incident;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.fleetops.trip.RiskBand;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    @Query("select i from Incident i where i.trip.id = :tripId and i.type = :type and i.status <> com.fleetops.incident.IncidentStatus.RESOLVED")
    Optional<Incident> findOpen(Long tripId, IncidentType type);

    @EntityGraph(attributePaths = {"trip", "vehicle", "shipment"})
    @Query("""
        select i from Incident i
        where i.status <> com.fleetops.incident.IncidentStatus.RESOLVED
        order by case i.severity when com.fleetops.trip.RiskBand.CRITICAL then 0
                                 when com.fleetops.trip.RiskBand.HIGH then 1
                                 when com.fleetops.trip.RiskBand.MEDIUM then 2 else 3 end,
                 i.detectedAt asc
        """)
    List<Incident> findOpenBySeverity();

    @EntityGraph(attributePaths = {"trip", "vehicle", "shipment"})
    @Query("""
        select i from Incident i
        where (:status is null or i.status = :status)
          and (:type is null or i.type = :type)
          and (:severity is null or i.severity = :severity)
        order by i.detectedAt desc
        """)
    Page<Incident> search(IncidentStatus status, IncidentType type, RiskBand severity, Pageable pageable);

    @EntityGraph(attributePaths = {"trip", "vehicle", "shipment"})
    Optional<Incident> findWithLinksById(Long id);

    List<Incident> findByTripIdAndStatusIn(Long tripId, Collection<IncidentStatus> statuses);

    List<Incident> findByTripIdOrderByDetectedAtDesc(Long tripId);

    @Query("select count(i) from Incident i where i.status <> com.fleetops.incident.IncidentStatus.RESOLVED")
    long countOpen();

    @Query(value = "select nextval('incident_code_seq')", nativeQuery = true)
    long nextCodeNumber();
}
