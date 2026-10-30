package com.fleetops.risk;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskEvaluationRepository extends JpaRepository<RiskEvaluation, Long> {

    List<RiskEvaluation> findByTripIdOrderByEvaluatedAtAsc(Long tripId);
}
