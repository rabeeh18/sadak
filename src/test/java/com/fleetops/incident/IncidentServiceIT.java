package com.fleetops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.fleetops.IntegrationTestSupport;
import com.fleetops.fleet.Warehouse;
import com.fleetops.trip.RiskBand;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripService;

class IncidentServiceIT extends IntegrationTestSupport {

    @Autowired IncidentService incidents;
    @Autowired IncidentRepository repository;
    @Autowired TripService trips;

    private long tripId;

    @BeforeEach
    void trip() {
        Warehouse w = warehouse();
        Trip t = trips.plan(new TripService.PlanRequest(w.getId(), List.of(shipment(w, 30.8, 75.2, 100).getId()),
            vehicle(w, 2500).getId(), driver(w, "Test Driver").getId(), clock.now(), true));
        tripId = t.getId();
    }

    private IncidentService.Detection slaRisk(RiskBand band) {
        return new IncidentService.Detection(tripId, IncidentType.SLA_RISK, band, "2 deliveries projected late", "detail", null, null);
    }

    @Test
    void repeatDetectionsUpdateTheOpenIncident() {
        incidents.detect(slaRisk(RiskBand.HIGH));
        incidents.detect(slaRisk(RiskBand.CRITICAL));
        incidents.detect(slaRisk(RiskBand.CRITICAL));
        List<Incident> all = repository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getDetectionCount()).isEqualTo(3);
        assertThat(all.get(0).getSeverity()).isEqualTo(RiskBand.CRITICAL);
    }

    @Test
    void concurrentDetectionsStillProduceOneIncident() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<Incident> task = () -> incidents.detect(slaRisk(RiskBand.HIGH));
            List<Future<Incident>> results = pool.invokeAll(List.of(task, task, task, task));
            for (Future<Incident> f : results) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void theDatabaseRejectsASecondOpenIncidentOfTheSameType() {
        incidents.detect(slaRisk(RiskBand.HIGH));
        Trip trip = trips.get(tripId);
        assertThatThrownBy(() -> repository.saveAndFlush(new Incident("INC-X", IncidentType.SLA_RISK, RiskBand.HIGH,
                trip, null, null, "dup", null, clock.now())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUnclaimedIncidentClearsItselfButAClaimedOneWaitsForTheDispatcher() {
        Incident first = incidents.detect(slaRisk(RiskBand.HIGH));
        incidents.conditionCleared(tripId, IncidentType.SLA_RISK);
        assertThat(repository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(IncidentStatus.RESOLVED);

        Incident second = incidents.detect(slaRisk(RiskBand.HIGH));
        assertThat(second.getId()).isNotEqualTo(first.getId());
        incidents.acknowledge(second.getId());
        incidents.conditionCleared(tripId, IncidentType.SLA_RISK);
        Incident after = repository.findById(second.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(after.isConditionCleared()).isTrue();
    }

    @Test
    void resolvingRequiresAnExplanationAndIsAudited() {
        Incident i = incidents.detect(slaRisk(RiskBand.HIGH));
        assertThatThrownBy(() -> incidents.resolve(i.getId(), " ")).hasMessageContaining("Describe");
        incidents.resolve(i.getId(), "Customer agreed to a later slot");
        assertThat(repository.findById(i.getId()).orElseThrow().getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'INCIDENT_RESOLVED'", Integer.class)).isEqualTo(1);
    }
}
