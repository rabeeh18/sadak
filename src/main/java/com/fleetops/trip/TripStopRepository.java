package com.fleetops.trip;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TripStopRepository extends JpaRepository<TripStop, Long> {

    List<TripStop> findByTripIdOrderBySeqAsc(Long tripId);

    Optional<TripStop> findByIdAndTripId(Long id, Long tripId);
}
