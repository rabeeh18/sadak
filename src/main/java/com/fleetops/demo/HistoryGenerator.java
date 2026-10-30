package com.fleetops.demo;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.common.geo.GeoPoint;

/**
 * Writes weeks of completed operations straight through JDBC, so analytics and historical
 * averages have something to work with on a fresh install. The patterns are deliberate and
 * discoverable: Moga and Gurdaspur legs run slow, Bathinda runs a little late, Monday mornings
 * are busy, and roughly one trip in twenty-five has a failed stop.
 */
@Component
@Profile("demo")
public class HistoryGenerator {

    private static final Logger log = LoggerFactory.getLogger(HistoryGenerator.class);
    private static final double PLAN_SPEED_KPH = 42;
    private static final double ROAD_FACTOR = 1.3;

    private final JdbcTemplate jdbc;
    private final ZoneId zone;
    private final Random random = new Random(42);

    public HistoryGenerator(JdbcTemplate jdbc, @Value("${fleet.display-zone}") String zone) {
        this.jdbc = jdbc;
        this.zone = ZoneId.of(zone);
    }

    private record Res(long id, long warehouseId, String warehouseCode) {
    }

    private record WarehouseRow(long id, String code, double lat, double lng) {
    }

    private final List<Object[]> tripRows = new ArrayList<>();
    private final List<Object[]> stopRows = new ArrayList<>();
    private final List<Object[]> shipmentRows = new ArrayList<>();
    private final List<Object[]> assignmentRows = new ArrayList<>();
    private final List<Object[]> proofRows = new ArrayList<>();
    private final List<Object[]> breachRows = new ArrayList<>();
    private final List<Object[]> incidentRows = new ArrayList<>();
    private final List<Object[]> actionRows = new ArrayList<>();
    private final List<Object[]> maintenanceRows = new ArrayList<>();

    @Transactional
    public void generate(Instant now, int days) {
        Map<String, WarehouseRow> warehouses = jdbc.query("select id, code, lat, lng from warehouses",
                (rs, i) -> new WarehouseRow(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4)))
            .stream().collect(Collectors.toMap(WarehouseRow::code, w -> w));
        Map<Long, List<Res>> vehiclesByWh = jdbc.query("select v.id, v.home_warehouse_id, w.code from vehicles v join warehouses w on w.id = v.home_warehouse_id",
            (rs, i) -> new Res(rs.getLong(1), rs.getLong(2), rs.getString(3))).stream().collect(Collectors.groupingBy(Res::warehouseId));
        Map<Long, List<Res>> driversByWh = jdbc.query("select d.id, d.home_warehouse_id, w.code from drivers d join warehouses w on w.id = d.home_warehouse_id where d.license_expiry > current_date - 400",
            (rs, i) -> new Res(rs.getLong(1), rs.getLong(2), rs.getString(3))).stream().collect(Collectors.groupingBy(Res::warehouseId));
        List<String> dispatchers = List.of("dispatcher", "dispatcher2", "admin");

        // Estimate the volume first so ids can be reserved from the sequences in bulk.
        int estTrips = days * warehouses.size() * 4 + 50;
        Deque<Long> tripIds = reserve("trips_id_seq", estTrips);
        Deque<Long> tripCodes = reserve("trip_code_seq", estTrips);
        Deque<Long> stopIds = reserve("trip_stops_id_seq", estTrips * 6);
        Deque<Long> shipmentIds = reserve("shipments_id_seq", estTrips * 12);
        Deque<Long> trackingNos = reserve("tracking_no_seq", estTrips * 12);
        Deque<Long> incidentCodes = reserve("incident_code_seq", estTrips);

        LocalDate today = LocalDate.ofInstant(now, zone);
        int tripCount = 0;
        for (int back = days; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            boolean sunday = day.getDayOfWeek() == DayOfWeek.SUNDAY;
            boolean monday = day.getDayOfWeek() == DayOfWeek.MONDAY;
            for (WarehouseRow w : warehouses.values()) {
                int count = sunday ? 1 + random.nextInt(2) : (monday ? 3 + random.nextInt(2) : 2 + random.nextInt(3));
                List<Res> vs = vehiclesByWh.getOrDefault(w.id(), List.of());
                List<Res> ds = driversByWh.getOrDefault(w.id(), List.of());
                if (vs.isEmpty() || ds.isEmpty()) {
                    continue;
                }
                for (int k = 0; k < Math.min(count, Math.min(vs.size(), ds.size())); k++) {
                    Res vehicle = vs.get((k + back) % vs.size());
                    Res driver = ds.get((k + back * 3) % ds.size());
                    Instant start = day.atTime(8, 15).atZone(zone).toInstant()
                        .plus(Duration.ofMinutes(k * 55L + random.nextInt(40)));
                    buildTrip(w, vehicle.id(), driver.id(), start, tripIds.pop(), tripCodes.pop(), stopIds, shipmentIds,
                        trackingNos, incidentCodes, dispatchers);
                    tripCount++;
                }
            }
        }
        seedMaintenance(vehiclesByWh, today, days);
        flush();
        log.info("History generated: {} trips over {} days", tripCount, days);
    }

    private Deque<Long> reserve(String sequence, int count) {
        return new ArrayDeque<>(jdbc.queryForList("select nextval('" + sequence + "') from generate_series(1, ?)", Long.class, count));
    }

    private void buildTrip(WarehouseRow w, long vehicleId, long driverId, Instant plannedStart, long tripId, long codeNo,
                           Deque<Long> stopIds, Deque<Long> shipmentIds, Deque<Long> trackingNos, Deque<Long> incidentCodes,
                           List<String> dispatchers) {
        List<DemoData.TownSeed> towns = DemoData.TOWNS.stream().filter(t -> t.warehouseCode().equals(w.code())).collect(Collectors.toCollection(ArrayList::new));
        java.util.Collections.shuffle(towns, random);
        int townCount = 1 + random.nextInt(2);
        int stopsPerTown = 1 + random.nextInt(3);

        double warehouseBias = w.code().equals("BTI") ? 1.10 : 1.0;
        GeoPoint prev = new GeoPoint(w.lat(), w.lng());
        Instant plannedClock = plannedStart;
        Instant actualClock = plannedStart.plus(Duration.ofMinutes(random.nextInt(12)));
        Instant actualStart = actualClock;
        int seq = 1;
        int lateCount = 0;
        int failedStops = 0;
        int worstLate = 0;
        int totalWeight = 0;
        boolean breakdown = random.nextDouble() < 0.025;
        int breakdownAtSeq = 2;
        String lastTown = "";

        for (int t = 0; t < townCount; t++) {
            DemoData.TownSeed town = towns.get(t);
            lastTown = town.town();
            double townBias = switch (town.town()) {
                case "Moga" -> 1.24;
                case "Gurdaspur" -> 1.16;
                case "Zirakpur" -> 1.12;
                default -> 1.0;
            };
            for (int s = 0; s < stopsPerTown; s++) {
                String street = DemoData.STREETS.get(random.nextInt(DemoData.STREETS.size()));
                GeoPoint loc = new GeoPoint(town.lat() + (random.nextDouble() - 0.5) * 0.012, town.lng() + (random.nextDouble() - 0.5) * 0.012);
                double km = prev.distanceTo(loc) / 1000 * ROAD_FACTOR;
                long plannedDrive = Math.round(km / PLAN_SPEED_KPH * 3600);
                long actualDrive = Math.round(plannedDrive * townBias * warehouseBias * (0.92 + random.nextDouble() * 0.23));
                int service = 8 + random.nextInt(5);
                long actualService = Math.round(service * 60 * (0.8 + random.nextDouble() * 0.8));

                plannedClock = plannedClock.plusSeconds(plannedDrive);
                Instant plannedArrival = plannedClock;
                plannedClock = plannedClock.plus(Duration.ofMinutes(service));
                actualClock = actualClock.plusSeconds(actualDrive);
                if (breakdown && seq == breakdownAtSeq) {
                    actualClock = actualClock.plus(Duration.ofMinutes(70 + random.nextInt(60)));
                }
                Instant arrival = actualClock;
                actualClock = actualClock.plusSeconds(actualService);
                Instant departure = actualClock;

                boolean failed = random.nextDouble() < 0.035;
                long stopId = stopIds.pop();
                String business = DemoData.BUSINESS_PREFIXES.get(random.nextInt(DemoData.BUSINESS_PREFIXES.size())) + " "
                    + DemoData.BUSINESS_KINDS.get(random.nextInt(DemoData.BUSINESS_KINDS.size()));
                String name = town.town() + " — " + business;
                String address = street + ", " + town.town() + ", Punjab " + town.pin();
                stopRows.add(new Object[] {stopId, tripId, seq, "DELIVERY", name, address, loc.lat(), loc.lng(), service,
                    ts(plannedArrival), ts(arrival), ts(arrival), ts(departure), failed ? "FAILED" : "COMPLETED"});
                if (!failed) {
                    proofRows.add(new Object[] {stopId, recipientName(), null, loc.lat(), loc.lng(), "system", ts(departure.minusSeconds(60))});
                } else {
                    failedStops++;
                }

                int perStop = 1 + random.nextInt(2);
                for (int p = 0; p < perStop; p++) {
                    long shipmentId = shipmentIds.pop();
                    int weight = 40 + random.nextInt(320);
                    totalWeight += weight;
                    Instant deadline = plannedArrival.plus(Duration.ofMinutes(25 + random.nextInt(70)));
                    Instant delivered = failed ? null : departure.minusSeconds(120);
                    boolean late = delivered != null && delivered.isAfter(deadline);
                    String reason = failed ? DemoData.FAILURE_REASONS.get(random.nextInt(DemoData.FAILURE_REASONS.size())) : null;
                    shipmentRows.add(new Object[] {shipmentId, "FL" + trackingNos.pop(), w.id(), recipientName(), null,
                        name, address, town.pin(), loc.lat(), loc.lng(), weight, ts(deadline),
                        failed ? "FAILED" : "DELIVERED", tripId, stopId, ts(delivered), reason, 1, late || failed,
                        ts(plannedStart.minus(Duration.ofHours(18)))});
                    if (late) {
                        int minutesLate = (int) Duration.between(deadline, delivered).toMinutes();
                        lateCount++;
                        worstLate = Math.max(worstLate, minutesLate);
                        breachRows.add(new Object[] {shipmentId, tripId, ts(deadline), ts(delivered), minutesLate, "LATE_DELIVERY"});
                    }
                    if (failed) {
                        breachRows.add(new Object[] {shipmentId, tripId, ts(deadline), ts(departure),
                            (int) Math.max(0, Duration.between(deadline, departure).toMinutes()), "FAILED"});
                    }
                }
                if (failed) {
                    addIncident(incidentCodes.pop(), "FAILED_DELIVERY", "HIGH", tripId, vehicleId,
                        "Delivery failed at " + name + " (TR-" + codeNo + " stop " + seq + ")", departure, dispatchers,
                        "Customer contacted; re-attempt booked for next day");
                }
                prev = loc;
                seq++;
            }
        }
        Instant actualEnd = actualClock;
        int score = Math.min(100, (breakdown ? 60 : 0) + Math.min(45, worstLate * 3 / 2) + (failedStops > 0 ? 15 : 0));
        String band = score >= 75 ? "CRITICAL" : score >= 50 ? "HIGH" : score >= 25 ? "MEDIUM" : "LOW";
        tripRows.add(new Object[] {tripId, "TR-" + codeNo, w.id(), vehicleId, driverId, "COMPLETED", ts(plannedStart),
            ts(plannedClock), ts(actualStart), ts(actualEnd), totalWeight, score, band, ts(actualEnd), "system", ts(plannedStart.minus(Duration.ofHours(14)))});
        assignmentRows.add(new Object[] {tripId, vehicleId, driverId, "Initial assignment", "dispatcher", ts(plannedStart.minus(Duration.ofHours(14)))});

        if (lateCount > 0 && random.nextDouble() < 0.8) {
            Instant detected = actualEnd.minus(Duration.ofMinutes(40 + random.nextInt(60)));
            addIncident(incidentCodes.pop(), "SLA_RISK", worstLate > 25 ? "CRITICAL" : "HIGH", tripId, vehicleId,
                lateCount + " deliveries projected late on TR-" + codeNo + " towards " + lastTown, detected, dispatchers,
                "Customers informed of revised arrival; driver confirmed");
        }
        if (breakdown) {
            Instant detected = actualStart.plus(Duration.ofMinutes(50 + random.nextInt(40)));
            addIncident(incidentCodes.pop(), "BREAKDOWN", "CRITICAL", tripId, vehicleId,
                "Vehicle broke down on TR-" + codeNo, detected, dispatchers, "Remaining stops moved to a replacement vehicle");
            maintenanceRows.add(new Object[] {vehicleId, "BREAKDOWN", "Breakdown during TR-" + codeNo, ts(detected),
                ts(detected.plus(Duration.ofHours(6 + random.nextInt(30)))), "dispatcher"});
        } else if (random.nextDouble() < 0.03) {
            Instant detected = actualStart.plus(Duration.ofMinutes(30 + random.nextInt(60)));
            addIncident(incidentCodes.pop(), random.nextBoolean() ? "ROUTE_DEVIATION" : "LOCATION_STALE", "MEDIUM", tripId, vehicleId,
                "Vehicle on TR-" + codeNo + " needed checking", detected, dispatchers, "Driver confirmed diversion around road works");
        }
    }

    private void addIncident(long codeNo, String type, String severity, long tripId, long vehicleId, String title,
                             Instant detected, List<String> dispatchers, String resolution) {
        String owner = dispatchers.get(random.nextInt(dispatchers.size()));
        Instant ack = detected.plus(Duration.ofMinutes(1 + random.nextInt(9)));
        Instant resolved = ack.plus(Duration.ofMinutes(8 + random.nextInt(severity.equals("CRITICAL") ? 110 : 60)));
        String code = "INC-" + codeNo;
        incidentRows.add(new Object[] {code, type, severity, "RESOLVED", tripId, vehicleId, title, ts(detected), ts(detected),
            ts(ack), owner, ts(resolved), resolution});
        actionRows.add(new Object[] {code, "ACKNOWLEDGED", null, owner, ts(ack)});
        actionRows.add(new Object[] {code, "CONTACTED_DRIVER", "Called driver for an update", owner, ts(ack.plus(Duration.ofMinutes(3)))});
        actionRows.add(new Object[] {code, "RESOLVED", resolution, owner, ts(resolved)});
    }

    private void seedMaintenance(Map<Long, List<Res>> vehiclesByWh, LocalDate today, int days) {
        for (List<Res> list : vehiclesByWh.values()) {
            for (Res v : list) {
                for (int back = days - random.nextInt(14); back > 2; back -= 18 + random.nextInt(10)) {
                    Instant start = today.minusDays(back).atTime(18, 30).atZone(zone).toInstant();
                    maintenanceRows.add(new Object[] {v.id(), "SCHEDULED", "Scheduled service", ts(start),
                        ts(start.plus(Duration.ofHours(5 + random.nextInt(8)))), "admin"});
                }
            }
        }
    }

    private String recipientName() {
        return DemoData.DRIVER_NAMES.get(random.nextInt(DemoData.DRIVER_NAMES.size())).split(" ")[0] + " "
            + DemoData.BUSINESS_PREFIXES.get(random.nextInt(DemoData.BUSINESS_PREFIXES.size()));
    }

    private void flush() {
        jdbc.batchUpdate("""
            insert into trips (id, code, warehouse_id, vehicle_id, driver_id, status, planned_start, planned_end,
                actual_start, actual_end, total_weight_kg, risk_score, risk_band, risk_evaluated_at, created_by, created_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", tripRows);
        jdbc.batchUpdate("""
            insert into trip_stops (id, trip_id, seq, kind, name, address, lat, lng, service_min, planned_arrival, eta,
                actual_arrival, actual_departure, status)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", stopRows);
        jdbc.batchUpdate("""
            insert into shipments (id, tracking_no, origin_warehouse_id, recipient_name, recipient_phone, dest_name,
                dest_address, dest_pin, dest_lat, dest_lng, weight_kg, deadline, status, trip_id, stop_id, delivered_at,
                failure_reason, attempts, was_delayed, created_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", shipmentRows);
        jdbc.batchUpdate("insert into trip_assignments (trip_id, vehicle_id, driver_id, reason, assigned_by, assigned_at) values (?,?,?,?,?,?)", assignmentRows);
        jdbc.batchUpdate("insert into delivery_proofs (stop_id, recipient_name, note, lat, lng, captured_by, captured_at) values (?,?,?,?,?,?,?)", proofRows);
        jdbc.batchUpdate("insert into sla_breach_events (shipment_id, trip_id, deadline, breached_at, minutes_late, kind) values (?,?,?,?,?,?)", breachRows);
        jdbc.batchUpdate("""
            insert into incidents (code, type, severity, status, trip_id, vehicle_id, title, detected_at, last_detected_at,
                acknowledged_at, owner_username, resolved_at, resolution)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?)""", incidentRows);
        jdbc.batchUpdate("""
            insert into incident_actions (incident_id, action, note, actor, performed_at)
            select id, ?, ?, ?, ? from incidents where code = ?""",
            actionRows.stream().map(r -> new Object[] {r[1], r[2], r[3], r[4], r[0]}).toList());
        jdbc.batchUpdate("insert into maintenance_records (vehicle_id, kind, reason, started_at, ended_at, created_by) values (?,?,?,?,?,?)", maintenanceRows);
        tripRows.clear();
        stopRows.clear();
        shipmentRows.clear();
        assignmentRows.clear();
        proofRows.clear();
        breachRows.clear();
        incidentRows.clear();
        actionRows.clear();
        maintenanceRows.clear();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
