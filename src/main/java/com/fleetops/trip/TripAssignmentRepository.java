package com.fleetops.trip;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripAssignmentRepository extends JpaRepository<TripAssignment, Long> {

    @EntityGraph(attributePaths = {"vehicle", "driver"})
    List<TripAssignment> findByTripIdOrderByAssignedAtAsc(Long tripId);
}
