package com.fleetops.trip;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteVersionRepository extends JpaRepository<RouteVersion, Long> {

    Optional<RouteVersion> findByTripIdAndActiveTrue(Long tripId);

    List<RouteVersion> findByTripIdOrderByVersionNoAsc(Long tripId);
}
