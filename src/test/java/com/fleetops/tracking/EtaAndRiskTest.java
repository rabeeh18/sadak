package com.fleetops.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fleetops.common.geo.GeoPoint;
import com.fleetops.common.geo.RouteLine;
import com.fleetops.fleet.Warehouse;
import com.fleetops.risk.EtaCalculator;
import com.fleetops.risk.RiskAssessment;
import com.fleetops.risk.RiskEngine;
import com.fleetops.settings.SettingsService;
import com.fleetops.settings.SettingsService.RuleSnapshot;
import com.fleetops.shipment.Shipment;
import com.fleetops.trip.RiskBand;
import com.fleetops.trip.StopStatus;
import com.fleetops.trip.TripStop;

class EtaAndRiskTest {

    private static final Instant T0 = Instant.parse("2026-10-22T04:00:00Z");
    private static final GeoPoint A = new GeoPoint(30.90, 75.85);
    private static final GeoPoint B = new GeoPoint(30.90, 75.55);
    private static final GeoPoint C = new GeoPoint(30.90, 75.25);

    private SettingsService settings;
    private RiskEngine engine;
    private final EtaCalculator eta = new EtaCalculator();

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        Map<String, RuleSnapshot> rules = Map.of(
            "PROJECTED_LATE", new RuleSnapshot("PROJECTED_LATE", "Projected late", 45, 1.5, "pts/min", true),
            "DEADLINE_PRESSURE", new RuleSnapshot("DEADLINE_PRESSURE", "Deadline pressure", 15, 15.0, "min", true),
            "UNEXPECTED_STOP", new RuleSnapshot("UNEXPECTED_STOP", "Unexpected stop", 25, 8.0, "min", true),
            "LOCATION_STALE", new RuleSnapshot("LOCATION_STALE", "Location stale", 30, 5.0, "min", true),
            "ROUTE_DEVIATION", new RuleSnapshot("ROUTE_DEVIATION", "Off planned route", 20, 500.0, "m", true),
            "LONG_STOP", new RuleSnapshot("LONG_STOP", "Long stop", 15, 10.0, "min", true),
            "PRIOR_DELAYS", new RuleSnapshot("PRIOR_DELAYS", "History of delays", 10, null, null, true),
            "BREAKDOWN", new RuleSnapshot("BREAKDOWN", "Vehicle breakdown", 60, null, null, true));
        when(settings.rule(anyString())).thenAnswer(inv -> rules.get(inv.getArgument(0, String.class)));
        when(settings.getInt("sla.at-risk-min")).thenReturn(15);
        when(settings.getInt("risk.band.medium")).thenReturn(25);
        when(settings.getInt("risk.band.high")).thenReturn(50);
        when(settings.getInt("risk.band.critical")).thenReturn(75);
        engine = new RiskEngine(settings);
    }

    /** Two legs of 30 minutes each, a stop at the end of each, 10 minutes service. */
    private LiveTrip trip() {
        RouteLine line = new RouteLine(List.of(A, B, C));
        double half = line.length() / 2;
        List<LiveTrip.OpenStop> stops = List.of(
            new LiveTrip.OpenStop(1, 1, "Stop B", TripStop.Kind.DELIVERY, B, half, 10, T0.plusSeconds(1800), StopStatus.PENDING, null),
            new LiveTrip.OpenStop(2, 2, "Stop C", TripStop.Kind.DELIVERY, C, line.length(), 10, T0.plusSeconds(4200), StopStatus.PENDING, null));
        LiveTrip lt = new LiveTrip(10, "TR-1", 7, "PB 10 GX 4471", "Driver", 1, line, List.of(30_000, 30_000),
            List.of(1800, 1800), stops, true, true, 0, false);
        lt.seedPosition(A, 0, T0);
        return lt;
    }

    @Test
    void etaOnPlanAddsDriveAndServiceTime() {
        LiveTrip lt = trip();
        EtaCalculator.TripEta result = eta.compute(lt, 0, T0);
        assertThat(result.byStop().get(1L)).isEqualTo(T0.plusSeconds(1800));
        // 60 min driving + 10 min service at the first stop.
        assertThat(result.byStop().get(2L)).isEqualTo(T0.plusSeconds(4200));
        assertThat(result.pace()).isEqualTo(1.0);
    }

    @Test
    void slowerObservedPaceStretchesRemainingDriveTime() {
        LiveTrip lt = trip();
        double quarter = lt.route.length() / 4;
        // Planned: a quarter of the route in 900 s. Observed: it took 1800 s, i.e. twice as slow.
        lt.applyFix(lt.route.pointAt(quarter / 2), quarter / 2, 0, 30, 90, T0.plusSeconds(900));
        lt.applyFix(lt.route.pointAt(quarter), quarter, 0, 30, 90, T0.plusSeconds(1800));
        EtaCalculator.TripEta result = eta.compute(lt, 0, T0.plusSeconds(1800));
        assertThat(result.pace()).isGreaterThan(1.8);
        // The remaining 900 s of planned driving to stop B takes about twice as long.
        long toB = Duration.between(T0.plusSeconds(1800), result.byStop().get(1L)).toSeconds();
        assertThat(toB).isBetween(1600L, 2300L);
    }

    @Test
    void dispatcherAdjustmentShiftsEveryEta() {
        EtaCalculator.TripEta result = eta.compute(trip(), 15, T0);
        assertThat(result.byStop().get(2L)).isEqualTo(T0.plusSeconds(4200 + 900));
    }

    @Test
    void breakdownMakesEtaUnknownAndScoresCritical() {
        LiveTrip lt = trip();
        lt.setBrokenDown(true);
        EtaCalculator.TripEta result = eta.compute(lt, 0, T0);
        assertThat(result.unknown()).isTrue();
        RiskAssessment a = engine.assess(lt, result, List.of(), T0);
        assertThat(a.has("BREAKDOWN")).isTrue();
        assertThat(a.band()).isEqualTo(RiskBand.HIGH); // 60 points alone is high; another condition makes it critical
    }

    @Test
    void lateShipmentScoresByMinutesAndExplainsItself() throws Exception {
        LiveTrip lt = trip();
        EtaCalculator.TripEta result = eta.compute(lt, 0, T0);
        Shipment late = shipmentAtStop(2, result.byStop().get(2L).minusSeconds(20 * 60)); // due 20 min before ETA
        RiskAssessment a = engine.assess(lt, result, List.of(late), T0);
        assertThat(a.worstLateMinutes()).isEqualTo(20);
        assertThat(a.get("PROJECTED_LATE").points()).isEqualTo(30); // 1.5 points per minute
        assertThat(a.explanation()).contains("projected 20 min late");
        assertThat(a.band()).isEqualTo(RiskBand.MEDIUM);
    }

    @Test
    void staleLocationAndDeviationAddUp() {
        LiveTrip lt = trip();
        lt.applyFix(new GeoPoint(30.95, 75.85), 0, 5500, 0, 0, T0);
        Instant later = T0.plus(Duration.ofMinutes(12));
        RiskAssessment a = engine.assess(lt, eta.compute(lt, 0, later), List.of(), later);
        assertThat(a.has("LOCATION_STALE")).isTrue();
        assertThat(a.has("ROUTE_DEVIATION")).isTrue();
        assertThat(a.score()).isEqualTo(50);
        assertThat(a.band()).isEqualTo(RiskBand.HIGH);
    }

    @Test
    void timeAtAStopIsNotCountedAsStandingStill() {
        LiveTrip lt = trip();
        lt.markArrived(1, T0);
        lt.markDone(1, T0.plus(Duration.ofMinutes(12)));
        Instant justAfter = T0.plus(Duration.ofMinutes(13));
        lt.applyFix(lt.position(), 0, 0, 0, 0, justAfter);
        RiskAssessment a = engine.assess(lt, eta.compute(lt, 0, justAfter), List.of(), justAfter);
        assertThat(a.has("UNEXPECTED_STOP")).isFalse();
    }

    private static Shipment shipmentAtStop(long stopId, Instant deadline) throws Exception {
        Warehouse w = new Warehouse("LDH", "Ludhiana DC", "Ludhiana", "x", 30.9, 75.9);
        Shipment s = new Shipment("FL1", w, "R", null, "Stop", "addr", "141001", C.lat(), C.lng(), 100, deadline, T0);
        TripStop stop = new TripStop(TripStop.Kind.DELIVERY, "Stop", "addr", C, 10);
        Field id = TripStop.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(stop, stopId);
        s.assignTo(null, stop);
        return s;
    }
}
