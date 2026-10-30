package com.fleetops.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.incident.Incident;
import com.fleetops.incident.IncidentAction;
import com.fleetops.incident.IncidentRepository;
import com.fleetops.incident.IncidentService;
import com.fleetops.incident.IncidentStatus;
import com.fleetops.incident.IncidentType;
import com.fleetops.incident.RecommendationService;
import com.fleetops.risk.MonitoringService;
import com.fleetops.trip.RiskBand;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripService;

/**
 * Incident console. Corrective actions change the trip through {@link TripService} and are then
 * recorded against the incident, so the incident timeline shows exactly what was done and by whom.
 */
@Controller
public class IncidentsController {

    private final IncidentService incidents;
    private final IncidentRepository repository;
    private final TripService trips;
    private final RecommendationService recommendations;
    private final MonitoringService monitoring;
    private final OperationalClock clock;
    private final Fmt fmt;

    public IncidentsController(IncidentService incidents, IncidentRepository repository, TripService trips,
                               RecommendationService recommendations, MonitoringService monitoring,
                               OperationalClock clock, Fmt fmt) {
        this.incidents = incidents;
        this.repository = repository;
        this.trips = trips;
        this.recommendations = recommendations;
        this.monitoring = monitoring;
        this.clock = clock;
        this.fmt = fmt;
    }

    @GetMapping("/incidents")
    @Transactional(readOnly = true)
    public String list(@RequestParam(required = false) IncidentStatus status,
                       @RequestParam(required = false) IncidentType type,
                       @RequestParam(required = false) RiskBand severity,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("open", incidents.openBySeverity());
        model.addAttribute("page", repository.search(status, type, severity, PageRequest.of(Math.max(0, page), 40)));
        model.addAttribute("status", status);
        model.addAttribute("type", type);
        model.addAttribute("severity", severity);
        model.addAttribute("statuses", IncidentStatus.values());
        model.addAttribute("types", IncidentType.values());
        model.addAttribute("severities", RiskBand.values());
        return "incidents/list";
    }

    @GetMapping("/incidents/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable long id, Model model) {
        Incident incident = incidents.get(id);
        model.addAttribute("incident", incident);
        model.addAttribute("actions", incidents.actionsOf(id));
        model.addAttribute("openFor", fmt.duration(incident.openFor(clock.now())));
        if (incident.getTrip() != null) {
            Trip trip = trips.get(incident.getTrip().getId());
            trip.getStops().size(); // rendered after the transaction closes
            model.addAttribute("trip", trip);
            model.addAttribute("live", monitoring.summary(trip.getId()));
            if (trip.getStatus().isLive() && incident.getStatus().isOpen()) {
                model.addAttribute("recommendation", recommendations.forTrip(trip.getId()));
            }
        }
        return "incidents/detail";
    }

    @PostMapping("/incidents/{id}/acknowledge")
    public String acknowledge(@PathVariable long id, RedirectAttributes ra) {
        incidents.acknowledge(id);
        ra.addFlashAttribute("ok", "You now own this incident.");
        return back(id);
    }

    @PostMapping("/incidents/{id}/contact")
    public String contact(@PathVariable long id, @RequestParam String note, RedirectAttributes ra) {
        requireText(note, "Write down what the driver said");
        incidents.recordAction(id, IncidentAction.Kind.CONTACTED_DRIVER, note);
        ra.addFlashAttribute("ok", "Driver contact recorded.");
        return back(id);
    }

    @PostMapping("/incidents/{id}/note")
    public String note(@PathVariable long id, @RequestParam String note, RedirectAttributes ra) {
        requireText(note, "The note is empty");
        incidents.recordAction(id, IncidentAction.Kind.NOTE, note);
        return back(id);
    }

    @PostMapping("/incidents/{id}/adjust-eta")
    public String adjustEta(@PathVariable long id, @RequestParam int minutes, @RequestParam(required = false) String note, RedirectAttributes ra) {
        Incident i = incidents.get(id);
        trips.adjustDelay(tripId(i), minutes, note);
        incidents.recordAction(id, IncidentAction.Kind.ADJUSTED_ETA, fmt.delta(minutes) + (note == null || note.isBlank() ? "" : ": " + note));
        ra.addFlashAttribute("ok", "ETA adjusted by " + fmt.delta(minutes) + ".");
        return back(id);
    }

    @PostMapping("/incidents/{id}/recalculate")
    public String recalculate(@PathVariable long id, RedirectAttributes ra) {
        Incident i = incidents.get(id);
        trips.recalculateRoute(tripId(i), "Recalculated for " + i.getCode());
        incidents.recordAction(id, IncidentAction.Kind.RECALCULATED_ROUTE, "New route from the vehicle's current position");
        ra.addFlashAttribute("ok", "Route recalculated.");
        return back(id);
    }

    @PostMapping("/incidents/{id}/reassign")
    public String reassign(@PathVariable long id, @RequestParam long vehicleId, @RequestParam long driverId,
                           @RequestParam(required = false) String reason, RedirectAttributes ra) {
        Incident i = incidents.get(id);
        Trip before = trips.get(tripId(i));
        String from = before.getVehicle().getRegistration();
        trips.reassign(tripId(i), vehicleId, driverId, reason == null || reason.isBlank() ? "Reassigned for " + i.getCode() : reason);
        Trip after = trips.get(tripId(i));
        incidents.recordAction(id, IncidentAction.Kind.REASSIGNED,
            from + " → " + after.getVehicle().getRegistration() + " with " + after.getDriver().getFullName());
        ra.addFlashAttribute("ok", "Remaining stops moved to " + after.getVehicle().getRegistration() + ".");
        return back(id);
    }

    @PostMapping("/incidents/{id}/fail-stop")
    public String failStop(@PathVariable long id, @RequestParam long stopId, @RequestParam String reason, RedirectAttributes ra) {
        Incident i = incidents.get(id);
        trips.failStop(tripId(i), stopId, reason);
        incidents.recordAction(id, IncidentAction.Kind.MARKED_FAILED, reason);
        ra.addFlashAttribute("ok", "Delivery marked as failed.");
        return back(id);
    }

    @PostMapping("/incidents/{id}/escalate")
    public String escalate(@PathVariable long id, @RequestParam(required = false) String note, RedirectAttributes ra) {
        incidents.escalate(id, note);
        ra.addFlashAttribute("ok", "Escalated. Admins have been notified.");
        return back(id);
    }

    @PostMapping("/incidents/{id}/resolve")
    public String resolve(@PathVariable long id, @RequestParam String resolution, RedirectAttributes ra) {
        incidents.resolve(id, resolution);
        ra.addFlashAttribute("ok", "Incident resolved.");
        return back(id);
    }

    private static long tripId(Incident i) {
        if (i.getTrip() == null) {
            throw new BusinessRuleException(i.getCode() + " is not linked to a trip");
        }
        return i.getTrip().getId();
    }

    private static void requireText(String s, String message) {
        if (s == null || s.isBlank()) {
            throw new BusinessRuleException(message);
        }
    }

    private static String back(long id) {
        return "redirect:/incidents/" + id;
    }
}
