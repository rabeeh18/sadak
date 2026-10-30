package com.fleetops.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fleetops.common.geo.GeoPoint;
import com.fleetops.fleet.DriverRepository;
import com.fleetops.fleet.VehicleRepository;
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.history.StatusHistoryService;
import com.fleetops.incident.IncidentRepository;
import com.fleetops.incident.RecommendationService;
import com.fleetops.risk.MonitoringService;
import com.fleetops.risk.RiskEvaluationRepository;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.trip.DeliveryProof;
import com.fleetops.trip.DeliveryProofRepository;
import com.fleetops.trip.RouteVersionRepository;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripAssignmentRepository;
import com.fleetops.trip.TripRepository;
import com.fleetops.trip.TripService;
import com.fleetops.trip.TripStatus;
import com.fleetops.trip.TripStop;

@Controller
public class TripsController {

    private final TripRepository trips;
    private final TripService tripService;
    private final ShipmentRepository shipments;
    private final RouteVersionRepository routes;
    private final TripAssignmentRepository assignments;
    private final DeliveryProofRepository proofs;
    private final IncidentRepository incidents;
    private final RiskEvaluationRepository evaluations;
    private final StatusHistoryService history;
    private final RecommendationService recommendations;
    private final MonitoringService monitoring;
    private final WarehouseRepository warehouses;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final Fmt fmt;

    public TripsController(TripRepository trips, TripService tripService, ShipmentRepository shipments,
                           RouteVersionRepository routes, TripAssignmentRepository assignments,
                           DeliveryProofRepository proofs, IncidentRepository incidents,
                           RiskEvaluationRepository evaluations, StatusHistoryService history,
                           RecommendationService recommendations, MonitoringService monitoring,
                           WarehouseRepository warehouses, VehicleRepository vehicles, DriverRepository drivers, Fmt fmt) {
        this.trips = trips;
        this.tripService = tripService;
        this.shipments = shipments;
        this.routes = routes;
        this.assignments = assignments;
        this.proofs = proofs;
        this.incidents = incidents;
        this.evaluations = evaluations;
        this.history = history;
        this.recommendations = recommendations;
        this.monitoring = monitoring;
        this.warehouses = warehouses;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.fmt = fmt;
    }

    @GetMapping("/trips")
    @Transactional(readOnly = true)
    public String list(@RequestParam(required = false) TripStatus status,
                       @RequestParam(required = false) Long warehouse,
                       @RequestParam(required = false) Long vehicle,
                       @RequestParam(required = false) Long driver,
                       @RequestParam(required = false) LocalDate from,
                       @RequestParam(required = false) LocalDate to,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        LocalDate today = fmt.today();
        LocalDate f = from != null ? from : today.minusDays(6);
        LocalDate t = to != null ? to : today.plusDays(1);
        Instant fi = f.atStartOfDay(fmt.zone()).toInstant();
        Instant ti = t.plusDays(1).atStartOfDay(fmt.zone()).toInstant();
        Page<Trip> result = trips.search(status, warehouse, vehicle, driver, fi, ti, PageRequest.of(Math.max(0, page), 40));
        result.forEach(trip -> trip.getStops().size()); // rendered after the transaction closes
        model.addAttribute("page", result);
        model.addAttribute("status", status);
        model.addAttribute("warehouse", warehouse);
        model.addAttribute("vehicle", vehicle);
        model.addAttribute("driver", driver);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("statuses", TripStatus.values());
        model.addAttribute("warehouses", warehouses.findAllByOrderByNameAsc());
        model.addAttribute("vehicles", vehicles.findAllByOrderByRegistrationAsc());
        model.addAttribute("drivers", drivers.findAllByOrderByFullNameAsc());
        model.addAttribute("live", monitoring.summaries().stream().collect(Collectors.toMap(s -> s.tripId(), s -> s)));
        return "trips/list";
    }

    @GetMapping("/trips/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable long id, Model model) {
        Trip trip = tripService.get(id);
        List<Shipment> tripShipments = shipments.findByTripIdOrderByIdAsc(id);
        Map<Long, List<Shipment>> byStop = tripShipments.stream().filter(s -> s.getStop() != null)
            .collect(Collectors.groupingBy(s -> s.getStop().getId()));
        Map<Long, DeliveryProof> proofByStop = proofs.findByStopIdIn(trip.getStops().stream().map(TripStop::getId).toList())
            .stream().collect(Collectors.toMap(p -> p.getStop().getId(), p -> p, (a, b) -> b));
        model.addAttribute("trip", trip);
        model.addAttribute("byStop", byStop);
        model.addAttribute("proofByStop", proofByStop);
        model.addAttribute("shipmentCount", tripShipments.size());
        model.addAttribute("routes", routes.findByTripIdOrderByVersionNoAsc(id));
        model.addAttribute("assignments", assignments.findByTripIdOrderByAssignedAtAsc(id));
        model.addAttribute("incidents", incidents.findByTripIdOrderByDetectedAtDesc(id));
        model.addAttribute("riskHistory", evaluations.findByTripIdOrderByEvaluatedAtAsc(id));
        model.addAttribute("statusHistory", history.historyOf(StatusHistoryService.TRIP, id));
        model.addAttribute("live", monitoring.summary(id));
        if (trip.getStatus().isLive()) {
            model.addAttribute("recommendation", recommendations.forTrip(id));
        }
        return "trips/detail";
    }

    @PostMapping("/trips/{id}/start")
    public String start(@PathVariable long id, RedirectAttributes ra) {
        tripService.start(id);
        ra.addFlashAttribute("ok", "Trip started. The vehicle is now tracked live.");
        return "redirect:/trips/" + id;
    }

    @PostMapping("/trips/{id}/cancel")
    public String cancel(@PathVariable long id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        tripService.cancel(id, reason);
        ra.addFlashAttribute("ok", "Trip cancelled. Undelivered shipments are back in the planning queue.");
        return "redirect:/trips/" + id;
    }

    @PostMapping("/trips/{id}/adjust")
    public String adjust(@PathVariable long id, @RequestParam int minutes, @RequestParam(required = false) String note, RedirectAttributes ra) {
        tripService.adjustDelay(id, minutes, note);
        ra.addFlashAttribute("ok", "ETA adjustment saved: " + fmt.delta(minutes) + ".");
        return "redirect:/trips/" + id;
    }

    @PostMapping("/trips/{id}/recalculate")
    public String recalculate(@PathVariable long id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        tripService.recalculateRoute(id, reason);
        ra.addFlashAttribute("ok", "Route recalculated from the vehicle's current position.");
        return "redirect:/trips/" + id;
    }

    @PostMapping("/trips/{id}/reassign")
    public String reassign(@PathVariable long id, @RequestParam long vehicleId, @RequestParam long driverId,
                           @RequestParam(required = false) String reason, RedirectAttributes ra) {
        tripService.reassign(id, vehicleId, driverId, reason);
        ra.addFlashAttribute("ok", "Trip reassigned. The new route is live on the control tower.");
        return "redirect:/trips/" + id;
    }

    @PostMapping("/trips/{id}/stops/{stopId}/complete")
    public String completeStop(@PathVariable long id, @PathVariable long stopId, @RequestParam String recipient,
                               @RequestParam(required = false) String note, RedirectAttributes ra) {
        Trip trip = tripService.get(id);
        GeoPoint at = trip.getVehicle().lastPosition();
        tripService.completeStop(id, stopId, recipient, note == null || note.isBlank() ? "Recorded by dispatcher" : note, at);
        ra.addFlashAttribute("ok", "Delivery recorded.");
        return "redirect:/trips/" + id;
    }

    @PostMapping("/trips/{id}/stops/{stopId}/fail")
    public String failStop(@PathVariable long id, @PathVariable long stopId, @RequestParam String reason, RedirectAttributes ra) {
        tripService.failStop(id, stopId, reason);
        ra.addFlashAttribute("ok", "Delivery marked as failed. An incident was opened.");
        return "redirect:/trips/" + id;
    }
}
