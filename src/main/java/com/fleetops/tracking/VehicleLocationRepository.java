package com.fleetops.tracking;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface VehicleLocationRepository extends JpaRepository<VehicleLocation, Long> {

    List<VehicleLocation> findByTripIdOrderByRecordedAtAsc(Long tripId);

    @Modifying
    @Query("delete from VehicleLocation l where l.recordedAt < :cutoff")
    int deleteOlderThan(Instant cutoff);
}
