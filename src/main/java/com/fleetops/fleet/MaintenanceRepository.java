package com.fleetops.fleet;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceRepository extends JpaRepository<MaintenanceRecord, Long> {

    Optional<MaintenanceRecord> findByVehicleIdAndEndedAtIsNull(Long vehicleId);

    @EntityGraph(attributePaths = "vehicle")
    List<MaintenanceRecord> findTop100ByOrderByStartedAtDesc();

    List<MaintenanceRecord> findByVehicleIdOrderByStartedAtDesc(Long vehicleId);
}
