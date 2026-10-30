package com.fleetops.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fleetops.common.geo.GridReference;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.fleet.Vehicle;
import com.fleetops.fleet.VehicleRepository;
import com.fleetops.fleet.Warehouse;
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.incident.Incident;
import com.fleetops.incident.IncidentRepository;
import com.fleetops.incident.IncidentService;
import com.fleetops.risk.MonitoringService;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.simulation.ClockKeeper;
import com.fleetops.simulation.SimulationEngine;
import com.fleetops.tracking.FleetBroadcaster;
import com.fleetops.tracking.VehicleLocationRepository;
import com.fleetops.trip.RouteVersionRepository;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripService;
import com.fleetops.trip.TripStop;

/** The control tower page and the JSON it loads; live changes then arrive over STOMP. */
@Controller
public class TowerController {

    private final MonitoringService monitoring;
    private final KpiService kpis;
    private final WarehouseRepository warehouses;
    private final VehicleRepository vehicles;
    private final IncidentService incidents;
    private final IncidentRepository incidentRepository;
    private final TripService trips;
    private final RouteVersionRepository routes;
    private final ShipmentRepository shipments;
    private final VehicleLocationRepository locations;
    private final FleetBroadcaster broadcaster;
    private final SimulationEngine simulation;
    private final OperationalClock clock;
    private final ClockKeeper clockKeeper;

    public TowerController(MonitoringService monitoring, KpiService kpis, WarehouseRepository warehouses,
                           VehicleRepository vehicles, IncidentService incidents, IncidentRepository incidentRepository,
                           TripService trips, RouteVersionRepository routes, ShipmentRepository shipments,
                           VehicleLocationRepository locations, FleetBroadcaster broadcaster, SimulationEngine simulation,
                           OperationalClock clock, ClockKeeper clockKeeper) {
        this.monitoring = monitoring;
        this.kpis = kpis;
        this.warehouses = warehouses;
        this.vehicles = vehicles;
        this.incidents = incidents;
        this.incidentRepository = incidentRepository;
        this.trips = trips;
        this.routes = routes;
        this.shipments = shipments;
        this.locations = locations;
        this.broadcaster = broadcaster;
        this.simulation = simulation;
        this.clock = clock;
        this.clockKeeper = clockKeeper;
    }

    @GetMapping("/tower")
    public String tower(Model model) {
        model.addAttribute("kpi", kpis.compute());
        return "tower";
    }

    @GetMapping("/api/tower/snapshot")
    @ResponseBody
    @Transactional(readOnly = true)
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("t", clock.now().toEpochMilli());
        out.put("kpis", kpis.compute());
        out.put("trips", monitoring.summaries());
        out.put("warehouses", warehouses.findByActiveTrueOrderByNameAsc().stream().map(TowerController::warehouseJson).toList());
        out.put("vehicles", vehicles.findAllByOrderByRegistrationAsc().stream().map(TowerController::vehicleJson).toList());
        out.put("incidents", incidents.openBySeverity().stream().map(TowerController::incidentJson).toList());
        out.put("positions", broadcaster.positionsFrame());
        return out;
    }

    @GetMapping("/api/tower/kpis")
    @ResponseBody
    public Map<String, Object> kpis() {
        return kpis.compute();
    }

    /** Everything the side panel and map need to show one trip. */
    @GetMapping("/api/tower/trips/{id}")
    @ResponseBody
    @Transactional(readOnly = true)
    public Map<String, Object> trip(@PathVariable long id) {
        Trip trip = trips.get(id);
        Map<Long, List<Shipment>> byStop = shipments.findByTripIdOrderByIdAsc(id).stream()
            .filter(s -> s.getStop() != null)
            .collect(Collectors.groupingBy(s -> s.getStop().getId()));
        List<Map<String, Object>> stops = new ArrayList<>();
        for (TripStop s : trip.getStops()) {
            List<Shipment> list = byStop.getOrDefault(s.getId(), List.of());
            Instant deadline = list.stream().map(Shipment::getDeadline).min(Instant::compareTo).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("seq", s.getSeq());
            m.put("kind", s.getKind().name());
            m.put("name", s.getName());
            m.put("status", s.getStatus().name());
            m.put("lat", s.getLat());
            m.put("lng", s.getLng());
            m.put("plannedArrival", epoch(s.getPlannedArrival()));
            m.put("eta", epoch(s.getEta()));
            m.put("actualArrival", epoch(s.getActualArrival()));
            m.put("actualDeparture", epoch(s.getActualDeparture()));
            m.put("deadline", epoch(deadline));
            m.put("shipments", list.size());
            m.put("grid", GridReference.of(s.getLat(), s.getLng()));
            stops.add(m);
        }
        List<Map<String, Object>> routeVersions = routes.findByTripIdOrderByVersionNoAsc(id).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", r.getVersionNo());
            m.put("active", r.isActive());
            m.put("geometry", r.getGeometry());
            m.put("estimated", r.isEstimated());
            m.put("reason", r.getReason());
            m.put("distanceM", r.getDistanceM());
            return m;
        }).toList();
        List<double[]> trail = locations.findByTripIdOrderByRecordedAtAsc(id).stream()
            .map(l -> new double[] {round(l.getLat()), round(l.getLng())}).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", trip.getId());
        out.put("code", trip.getCode());
        out.put("status", trip.getStatus().name());
        out.put("vehicle", vehicleJson(trip.getVehicle()));
        out.put("driver", Map.of("id", trip.getDriver().getId(), "name", trip.getDriver().getFullName(), "phone", trip.getDriver().getPhone()));
        out.put("warehouse", warehouseJson(trip.getWarehouse()));
        out.put("plannedStart", epoch(trip.getPlannedStart()));
        out.put("actualStart", epoch(trip.getActualStart()));
        out.put("weightKg", trip.getTotalWeightKg());
        out.put("delayAdjustMin", trip.getDelayAdjustMin());
        out.put("stops", stops);
        out.put("routes", routeVersions);
        out.put("trail", trail);
        out.put("summary", monitoring.summary(id));
        out.put("scenario", simulation.scenarioOf(id).name());
        out.put("extraDwellMin", simulation.extraDwellOf(id));
        out.put("incidents", incidentRepository.findByTripIdOrderByDetectedAtDesc(id).stream()
            .filter(i -> i.getStatus().isOpen()).map(TowerController::incidentJson).toList());
        return out;
    }

    // ------------------------------------------------------------------ simulation controls

    public record SpeedRequest(double speed) {
    }

    public record ScenarioRequest(String scenario, Integer minutes) {
    }

    @PostMapping("/api/sim/speed")
    @ResponseBody
    public Map<String, Object> speed(@RequestBody SpeedRequest req) {
        clock.setSpeed(req.speed());
        clockKeeper.saveControls();
        return clockJson();
    }

    @PostMapping("/api/sim/pause")
    @ResponseBody
    public Map<String, Object> pause() {
        clock.pause();
        clockKeeper.saveControls();
        return clockJson();
    }

    @PostMapping("/api/sim/resume")
    @ResponseBody
    public Map<String, Object> resume() {
        clock.resume();
        clockKeeper.saveControls();
        return clockJson();
    }

    @PostMapping("/api/sim/trips/{id}/scenario")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> scenario(@PathVariable long id, @RequestBody ScenarioRequest req) {
        if ("EXTEND_STOP".equals(req.scenario())) {
            simulation.extendStop(id, req.minutes() == null ? 20 : req.minutes());
        } else {
            simulation.apply(id, SimulationEngine.Scenario.valueOf(req.scenario()));
        }
        return ResponseEntity.ok(Map.of("scenario", simulation.scenarioOf(id).name(), "extraDwellMin", simulation.extraDwellOf(id)));
    }

    private Map<String, Object> clockJson() {
        return Map.of("now", clock.now().toEpochMilli(), "speed", clock.speed(), "paused", clock.paused());
    }

    // ------------------------------------------------------------------ json helpers

    static Map<String, Object> warehouseJson(Warehouse w) {
        return Map.of("id", w.getId(), "code", w.getCode(), "name", w.getName(), "city", w.getCity(),
            "lat", w.getLat(), "lng", w.getLng(), "grid", GridReference.of(w.getLat(), w.getLng()));
    }

    static Map<String, Object> vehicleJson(Vehicle v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("registration", v.getRegistration());
        m.put("type", v.getType());
        m.put("capacityKg", v.getCapacityKg());
        m.put("status", v.getStatus().name());
        m.put("statusLabel", v.getStatus().label());
        m.put("lat", v.lastPosition().lat());
        m.put("lng", v.lastPosition().lng());
        m.put("warehouse", v.getHomeWarehouse().getCode());
        return m;
    }

    static Map<String, Object> incidentJson(Incident i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("code", i.getCode());
        m.put("type", i.getType().name());
        m.put("typeLabel", i.getType().label());
        m.put("severity", i.getSeverity().name());
        m.put("status", i.getStatus().name());
        m.put("statusLabel", i.getStatus().label());
        m.put("title", i.getTitle());
        m.put("tripId", i.getTrip() == null ? null : i.getTrip().getId());
        m.put("tripCode", i.getTrip() == null ? null : i.getTrip().getCode());
        m.put("detectedAt", epoch(i.getDetectedAt()));
        m.put("owner", i.getOwnerUsername());
        m.put("cleared", i.isConditionCleared());
        return m;
    }

    static Long epoch(Instant i) {
        return i == null ? null : i.toEpochMilli();
    }

    private static double round(double v) {
        return Math.round(v * 1e5) / 1e5;
    }
}
