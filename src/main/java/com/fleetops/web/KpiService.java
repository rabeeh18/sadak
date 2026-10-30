package com.fleetops.web;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fleetops.risk.MonitoringService;

/** Control tower headline numbers, all computed from the database (and live risk for "at risk"). */
@Service
public class KpiService {

    private final JdbcTemplate jdbc;
    private final MonitoringService monitoring;
    private final Fmt fmt;

    public KpiService(JdbcTemplate jdbc, MonitoringService monitoring, Fmt fmt) {
        this.jdbc = jdbc;
        this.monitoring = monitoring;
        this.fmt = fmt;
    }

    public Map<String, Object> compute() {
        LocalDate today = fmt.today();
        Timestamp from = Timestamp.from(today.atStartOfDay(fmt.zone()).toInstant());
        Timestamp to = Timestamp.from(today.plusDays(1).atStartOfDay(fmt.zone()).toInstant());

        Map<String, Object> trips = jdbc.queryForMap("""
            select count(*) filter (where status = 'IN_PROGRESS') as active,
                   count(*) filter (where status = 'PLANNED') as planned
            from trips where status in ('IN_PROGRESS', 'PLANNED')""");
        Map<String, Object> deliveries = jdbc.queryForMap("""
            select count(*) filter (where status = 'DELIVERED') as delivered,
                   count(*) filter (where status = 'FAILED') as failed,
                   count(*) filter (where status = 'DELIVERED' and delivered_at <= deadline) as on_time,
                   coalesce(avg(extract(epoch from (delivered_at - deadline)) / 60)
                            filter (where status = 'DELIVERED' and delivered_at > deadline), 0) as avg_late
            from shipments
            where (delivered_at >= ? and delivered_at < ?)
               or (status = 'FAILED' and stop_id in (select id from trip_stops where actual_departure >= ? and actual_departure < ?))""",
            from, to, from, to);
        Map<String, Object> vehicles = jdbc.queryForMap("""
            select count(*) filter (where status = 'AVAILABLE') as idle,
                   count(*) filter (where status in ('BROKEN_DOWN', 'MAINTENANCE')) as unavailable,
                   count(*) filter (where status <> 'RETIRED') as in_service
            from vehicles""");
        Map<String, Object> incidents = jdbc.queryForMap("""
            select count(*) as open, count(*) filter (where severity = 'CRITICAL') as critical
            from incidents where status <> 'RESOLVED'""");

        long delivered = ((Number) deliveries.get("delivered")).longValue();
        long onTime = ((Number) deliveries.get("on_time")).longValue();
        long failed = ((Number) deliveries.get("failed")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeTrips", trips.get("active"));
        out.put("plannedTrips", trips.get("planned"));
        out.put("deliveredToday", delivered);
        out.put("failedToday", failed);
        out.put("onTimeToday", delivered + failed == 0 ? null : Math.round(100.0 * onTime / (delivered + failed)));
        out.put("atRisk", monitoring.shipmentsAtRisk());
        out.put("openIncidents", incidents.get("open"));
        out.put("criticalIncidents", incidents.get("critical"));
        out.put("idleVehicles", vehicles.get("idle"));
        out.put("unavailableVehicles", vehicles.get("unavailable"));
        out.put("vehiclesInService", vehicles.get("in_service"));
        out.put("avgDelayMin", Math.round(((Number) deliveries.get("avg_late")).doubleValue()));
        out.put("asOf", Instant.now().toEpochMilli());
        return out;
    }
}
