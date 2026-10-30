package com.fleetops.web;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Operational analytics straight from SQL. Every metric has one written definition (shown on
 * the page) and all of them share the same trip filter, so the numbers always agree.
 */
@Service
public class AnalyticsService {

    /** Operating window used for fleet utilisation: 12 hours a day per vehicle in service. */
    public static final int OPERATING_HOURS_PER_DAY = 12;

    private final NamedParameterJdbcTemplate jdbc;
    private final Fmt fmt;

    public AnalyticsService(NamedParameterJdbcTemplate jdbc, Fmt fmt) {
        this.jdbc = jdbc;
        this.fmt = fmt;
    }

    public record Filter(LocalDate from, LocalDate to, Long warehouseId, Long vehicleId, Long driverId) {
    }

    private MapSqlParameterSource params(Filter f) {
        return new MapSqlParameterSource()
            .addValue("from", Timestamp.from(f.from().atStartOfDay(fmt.zone()).toInstant()))
            .addValue("to", Timestamp.from(f.to().plusDays(1).atStartOfDay(fmt.zone()).toInstant()))
            .addValue("wh", f.warehouseId())
            .addValue("veh", f.vehicleId())
            .addValue("drv", f.driverId())
            .addValue("zone", fmt.zone().getId());
    }

    /** The trips every metric is computed over. */
    private static final String TRIPS = """
        select t.* from trips t
        where t.planned_start >= :from and t.planned_start < :to
          and t.status in ('COMPLETED', 'IN_PROGRESS')
          and (cast(:wh as bigint) is null or t.warehouse_id = :wh)
          and (cast(:veh as bigint) is null or t.vehicle_id = :veh)
          and (cast(:drv as bigint) is null or t.driver_id = :drv)
        """;

    public Map<String, Object> summary(Filter f) {
        MapSqlParameterSource p = params(f);
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(jdbc.queryForMap("with ft as (" + TRIPS + """
            )
            select count(*) as trips,
                   count(*) filter (where status = 'COMPLETED') as completed,
                   coalesce(avg(extract(epoch from (actual_end - actual_start)) / nullif(extract(epoch from (planned_end - planned_start)), 0))
                            filter (where status = 'COMPLETED'), 1) as efficiency,
                   coalesce(sum(extract(epoch from (coalesce(actual_end, now()) - actual_start))) / 3600, 0) as trip_hours
            from ft""", p));
        out.putAll(jdbc.queryForMap("with ft as (" + TRIPS + """
            )
            select count(*) filter (where s.status = 'DELIVERED') as delivered,
                   count(*) filter (where s.status = 'FAILED') as failed,
                   count(*) filter (where s.status = 'DELIVERED' and s.delivered_at <= s.deadline) as on_time,
                   coalesce(avg(extract(epoch from (s.delivered_at - s.deadline)) / 60)
                            filter (where s.status = 'DELIVERED' and s.delivered_at > s.deadline), 0) as avg_late_min
            from shipments s join ft on ft.id = s.trip_id""", p));
        out.putAll(jdbc.queryForMap("with ft as (" + TRIPS + """
            )
            select count(*) as breaches from sla_breach_events b join ft on ft.id = b.trip_id""", p));
        out.putAll(jdbc.queryForMap("with ft as (" + TRIPS + """
            )
            select count(*) filter (where st.status = 'COMPLETED') as stops_done,
                   count(*) filter (where st.status in ('COMPLETED', 'FAILED', 'SKIPPED')) as stops_closed
            from trip_stops st join ft on ft.id = st.trip_id where st.kind = 'DELIVERY'""", p));
        out.putAll(jdbc.queryForMap("with ft as (" + TRIPS + """
            )
            select count(*) as incidents,
                   coalesce(avg(extract(epoch from (i.resolved_at - i.detected_at)) / 60) filter (where i.resolved_at is not null), 0) as resolve_min
            from incidents i join ft on ft.id = i.trip_id""", p));
        out.putAll(jdbc.queryForMap("""
            select coalesce(sum(extract(epoch from (least(coalesce(m.ended_at, :to), :to) - greatest(m.started_at, :from)))) / 3600, 0) as downtime_hours
            from maintenance_records m join vehicles v on v.id = m.vehicle_id
            where m.started_at < :to and coalesce(m.ended_at, :to) > :from
              and (cast(:wh as bigint) is null or v.home_warehouse_id = :wh)
              and (cast(:veh as bigint) is null or v.id = :veh)""", p));
        long vehiclesInScope = jdbc.queryForObject("""
            select count(*) from vehicles v where v.status <> 'RETIRED'
              and (cast(:wh as bigint) is null or v.home_warehouse_id = :wh)
              and (cast(:veh as bigint) is null or v.id = :veh)""", p, Long.class);

        long days = f.to().toEpochDay() - f.from().toEpochDay() + 1;
        long delivered = num(out, "delivered");
        long failed = num(out, "failed");
        long onTime = num(out, "on_time");
        double available = (double) vehiclesInScope * days * OPERATING_HOURS_PER_DAY;
        out.put("on_time_rate", delivered + failed == 0 ? null : 100.0 * onTime / (delivered + failed));
        out.put("utilisation", available == 0 ? null : 100.0 * ((Number) out.get("trip_hours")).doubleValue() / available);
        out.put("stop_completion", num(out, "stops_closed") == 0 ? null : 100.0 * num(out, "stops_done") / num(out, "stops_closed"));
        out.put("vehicles", vehiclesInScope);
        out.put("days", days);
        return out;
    }

    public List<Map<String, Object>> daily(Filter f) {
        return jdbc.queryForList("with ft as (" + TRIPS + """
            )
            select to_char(date_trunc('day', ft.planned_start at time zone :zone), 'YYYY-MM-DD') as day,
                   count(distinct ft.id) as trips,
                   count(s.id) filter (where s.status = 'DELIVERED') as delivered,
                   count(s.id) filter (where s.status = 'DELIVERED' and s.delivered_at <= s.deadline) as on_time,
                   count(s.id) filter (where s.status = 'FAILED' or (s.status = 'DELIVERED' and s.delivered_at > s.deadline)) as late_or_failed,
                   coalesce(round(avg(extract(epoch from (s.delivered_at - s.deadline)) / 60)
                       filter (where s.delivered_at > s.deadline)), 0) as avg_late
            from ft left join shipments s on s.trip_id = ft.id
            group by 1 order by 1""", params(f));
    }

    public List<Map<String, Object>> incidentsDaily(Filter f) {
        return jdbc.queryForList("with ft as (" + TRIPS + """
            )
            select to_char(date_trunc('day', i.detected_at at time zone :zone), 'YYYY-MM-DD') as day, i.type, count(*) as n
            from incidents i join ft on ft.id = i.trip_id group by 1, 2 order by 1""", params(f));
    }

    public List<Map<String, Object>> byWarehouse(Filter f) {
        return jdbc.queryForList("with ft as (" + TRIPS + """
            )
            select w.id, w.code, w.name, count(distinct ft.id) as trips,
                   count(s.id) filter (where s.status = 'DELIVERED') as delivered,
                   round(100.0 * count(s.id) filter (where s.status = 'DELIVERED' and s.delivered_at <= s.deadline)
                         / nullif(count(s.id) filter (where s.status in ('DELIVERED', 'FAILED')), 0), 1) as on_time_rate,
                   coalesce(round(avg(extract(epoch from (s.delivered_at - s.deadline)) / 60) filter (where s.delivered_at > s.deadline)), 0) as avg_late,
                   round(avg(extract(epoch from (ft.actual_end - ft.actual_start)) / nullif(extract(epoch from (ft.planned_end - ft.planned_start)), 0)), 3) as efficiency
            from ft join warehouses w on w.id = ft.warehouse_id left join shipments s on s.trip_id = ft.id
            group by w.id, w.code, w.name order by on_time_rate nulls last""", params(f));
    }

    /** Destination towns ranked by how late arrivals run against the plan: where the plan is wrong. */
    public List<Map<String, Object>> byTown(Filter f) {
        return jdbc.queryForList("with ft as (" + TRIPS + """
            )
            select split_part(st.name, ' — ', 1) as town, count(*) as stops,
                   round(avg(extract(epoch from (st.actual_arrival - st.planned_arrival)) / 60)) as avg_arrival_delay,
                   round(100.0 * count(*) filter (where st.actual_arrival <= st.planned_arrival + interval '15 minutes') / count(*), 1) as within_15
            from trip_stops st join ft on ft.id = st.trip_id
            where st.kind = 'DELIVERY' and st.actual_arrival is not null and st.planned_arrival is not null
            group by 1 having count(*) >= 5 order by avg_arrival_delay desc limit 12""", params(f));
    }

    public List<Map<String, Object>> byDriver(Filter f) {
        return jdbc.queryForList("with ft as (" + TRIPS + """
            )
            select d.id, d.full_name, count(distinct ft.id) as trips,
                   count(s.id) filter (where s.status = 'DELIVERED') as delivered,
                   round(100.0 * count(s.id) filter (where s.status = 'DELIVERED' and s.delivered_at <= s.deadline)
                         / nullif(count(s.id) filter (where s.status in ('DELIVERED', 'FAILED')), 0), 1) as on_time_rate,
                   count(s.id) filter (where s.status = 'FAILED') as failed
            from ft join drivers d on d.id = ft.driver_id left join shipments s on s.trip_id = ft.id
            group by d.id, d.full_name order by on_time_rate nulls last, trips desc limit 25""", params(f));
    }

    public List<Map<String, Object>> failureReasons(Filter f) {
        return jdbc.queryForList("with ft as (" + TRIPS + """
            )
            select s.failure_reason as reason, count(*) as n
            from shipments s join ft on ft.id = s.trip_id where s.status = 'FAILED' or s.failure_reason is not null
            group by 1 order by 2 desc""", params(f));
    }

    public List<Map<String, Object>> tripsForExport(Filter f) {
        return jdbc.queryForList("with ft as (" + TRIPS + """
            )
            select ft.code, w.code as warehouse, v.registration as vehicle, d.full_name as driver, ft.status,
                   to_char(ft.planned_start at time zone :zone, 'YYYY-MM-DD HH24:MI') as planned_start,
                   to_char(ft.actual_start at time zone :zone, 'YYYY-MM-DD HH24:MI') as actual_start,
                   to_char(ft.actual_end at time zone :zone, 'YYYY-MM-DD HH24:MI') as actual_end,
                   round(extract(epoch from (ft.planned_end - ft.planned_start)) / 60) as planned_min,
                   round(extract(epoch from (ft.actual_end - ft.actual_start)) / 60) as actual_min,
                   (select count(*) from trip_stops st where st.trip_id = ft.id and st.kind = 'DELIVERY') as stops,
                   (select count(*) from shipments s where s.trip_id = ft.id and s.status = 'DELIVERED') as delivered,
                   (select count(*) from shipments s where s.trip_id = ft.id and s.status = 'FAILED') as failed,
                   (select count(*) from shipments s where s.trip_id = ft.id and s.delivered_at > s.deadline) as late,
                   (select count(*) from incidents i where i.trip_id = ft.id) as incidents,
                   ft.risk_score
            from ft join warehouses w on w.id = ft.warehouse_id join vehicles v on v.id = ft.vehicle_id join drivers d on d.id = ft.driver_id
            order by ft.planned_start""", params(f));
    }

    private static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }
}
