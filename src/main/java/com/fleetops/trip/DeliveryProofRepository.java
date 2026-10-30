package com.fleetops.trip;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryProofRepository extends JpaRepository<DeliveryProof, Long> {

    List<DeliveryProof> findByStopIdIn(Collection<Long> stopIds);
}
