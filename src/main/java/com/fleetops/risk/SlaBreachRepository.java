package com.fleetops.risk;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaBreachRepository extends JpaRepository<SlaBreachEvent, Long> {

    boolean existsByShipmentIdAndKind(Long shipmentId, SlaBreachEvent.Kind kind);
}
