package com.fleetops.web;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fleetops.audit.AuditService;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.complaint.Complaint;
import com.fleetops.complaint.ComplaintRepository;
import com.fleetops.fleet.Warehouse;
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.notification.NotificationService;
import com.fleetops.security.Role;
import com.fleetops.security.StaffScope;
import com.fleetops.security.UserAccount;
import com.fleetops.security.UserAccountRepository;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.shipment.ShipmentService;
import com.fleetops.shipment.ShipmentStatus;
import com.fleetops.trip.RiskBand;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripRepository;
import com.fleetops.trip.TripStatus;

/**
 * The Warehouse role's screens: one warehouse's departures, parcels waiting and coming back,
 * customer complaints and notes, plus its shipment list and booking. Everything is limited to the
 * signed-in person's own warehouse (SecurityConfig limits /desk/** to the Warehouse role).
 */
@Controller
public class DeskController {

    private final StaffScope scope;
    private final WarehouseRepository warehouses;
    private final TripRepository trips;
    private final ShipmentRepository shipments;
    private final ShipmentService shipmentService;
    private final ComplaintRepository complaints;
    private final NotificationService notifications;
    private final AuditService audit;
    private final OperationalClock clock;
    private final Fmt fmt;
    private final UserAccountRepository users;

    public DeskController(StaffScope scope, WarehouseRepository warehouses, TripRepository trips, ShipmentRepository shipments,
                          ShipmentService shipmentService, ComplaintRepository complaints, NotificationService notifications,
                          AuditService audit, OperationalClock clock, Fmt fmt, UserAccountRepository users) {
        this.users = users;
        this.scope = scope;
        this.warehouses = warehouses;
        this.trips = trips;
        this.shipments = shipments;
        this.shipmentService = shipmentService;
        this.complaints = complaints;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
        this.fmt = fmt;
    }

    @GetMapping("/desk")
    @Transactional(readOnly = true)
    public String desk(Model model) {
        Warehouse w = own();
        Instant now = clock.now();
        Instant dayStart = now.atZone(fmt.zone()).toLocalDate().atStartOfDay(fmt.zone()).toInstant();

        List<Trip> departures = trips.departures(w.getId(), dayStart).stream()
            .filter(t -> t.getStatus() != TripStatus.CANCELLED).toList();
        departures.forEach(t -> t.getStops().size()); // progress is rendered after the transaction closes
        List<Shipment> waiting = shipments.findByOriginWarehouseIdAndStatusOrderByDeadlineAsc(w.getId(), ShipmentStatus.CREATED);
        List<Shipment> allFailed = shipments.findByOriginWarehouseIdAndStatusOrderByDeadlineAsc(w.getId(), ShipmentStatus.FAILED);
        // "Coming back" is today's failures: on a trip still out, or one that returned today. Older ones are in Shipments.
        List<Shipment> failed = allFailed.stream().filter(s -> s.getTrip() != null
            && (s.getTrip().getStatus().isLive() || s.getTrip().getActualEnd() != null && !s.getTrip().getActualEnd().isBefore(dayStart))).toList();
        failed.forEach(s -> {
            if (s.getTrip() != null) {
                s.getTrip().getDriver().getFullName();
            }
        });
        List<Complaint> open = complaints.list(w.getId(), EnumSet.of(Complaint.Status.NEW, Complaint.Status.IN_PROGRESS));
        List<Complaint> resolved = complaints.list(w.getId(), EnumSet.of(Complaint.Status.RESOLVED)).stream().limit(5).toList();
        open.forEach(c -> touchTrip(c.getShipment()));
        List<Shipment> notes = shipments.openNotes(w.getId());
        notes.forEach(s -> {
            if (s.getTrip() != null) {
                s.getTrip().getCode();
            }
        });

        model.addAttribute("w", w);
        model.addAttribute("deskOpen", w.deskOpenAt(now.atZone(fmt.zone()).toLocalTime()));
        model.addAttribute("departures", departures);
        model.addAttribute("departedToday", departures.stream().filter(t -> t.getActualStart() != null && !t.getActualStart().isBefore(dayStart)).count());
        model.addAttribute("waiting", waiting);
        model.addAttribute("dueSoon", waiting.stream().filter(s -> s.getDeadline().isBefore(now.plus(4, ChronoUnit.HOURS))).count());
        model.addAttribute("failed", failed);
        model.addAttribute("olderFailed", allFailed.size() - failed.size());
        model.addAttribute("openComplaints", open);
        model.addAttribute("resolvedComplaints", resolved);
        model.addAttribute("newComplaints", open.stream().filter(c -> c.getStatus() == Complaint.Status.NEW).count());
        model.addAttribute("notes", notes);
        model.addAttribute("names", users.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(UserAccount::getUsername, UserAccount::getFullName)));
        model.addAttribute("now", now);
        return "desk/desk";
    }

    @PostMapping("/desk/phone")
    @Transactional
    public String phone(@RequestParam String phone, @RequestParam String opens, @RequestParam String closes, RedirectAttributes ra) {
        Warehouse w = own();
        String number = phone == null ? "" : phone.strip();
        if (!number.matches("[+0-9][0-9 -]{5,18}")) {
            throw new BusinessRuleException("Enter the desk phone number, for example 0175 230 1234");
        }
        LocalTime from;
        LocalTime to;
        try {
            from = LocalTime.parse(opens);
            to = LocalTime.parse(closes);
        } catch (DateTimeParseException e) {
            throw new BusinessRuleException("Enter opening and closing times");
        }
        if (!to.isAfter(from)) {
            throw new BusinessRuleException("The desk has to close after it opens");
        }
        w.updateDesk(number, from, to);
        audit.record("WAREHOUSE_DESK_UPDATED", "WAREHOUSE", w.getId(), w.getName() + " desk: " + number + ", " + from + "–" + to);
        ra.addFlashAttribute("ok", "Desk phone and hours saved. Customers see them on the tracking page.");
        return "redirect:/desk";
    }

    /** Lets the control room know parcels are waiting; planning itself stays with dispatchers. */
    @PostMapping("/desk/ask-plan")
    public String askPlan(RedirectAttributes ra) {
        Warehouse w = own();
        List<Shipment> waiting = shipments.findByOriginWarehouseIdAndStatusOrderByDeadlineAsc(w.getId(), ShipmentStatus.CREATED);
        if (waiting.isEmpty()) {
            throw new BusinessRuleException("Nothing is waiting to go out");
        }
        notifications.notifyRoles(List.of(Role.DISPATCHER), RiskBand.MEDIUM,
            w.getName() + " asks for planning", waiting.size() + " parcel" + (waiting.size() == 1 ? "" : "s") + " waiting, the first due "
                + fmt.when(waiting.get(0).getDeadline()), "/planning");
        ra.addFlashAttribute("ok", "Dispatch has been asked to plan the waiting parcels.");
        return "redirect:/desk";
    }

    @PostMapping("/desk/shipments/{id}/rebook")
    @Transactional
    public String rebook(@PathVariable long id, @RequestParam(defaultValue = "/desk") String back, RedirectAttributes ra) {
        Shipment s = mine(id);
        shipmentService.requeue(s.getId(), "Re-booked by the " + s.getOriginWarehouse().getCity() + " desk for another attempt");
        ra.addFlashAttribute("ok", s.getTrackingNo() + " is back in the planning queue for another attempt.");
        return "redirect:" + safeBack(back);
    }

    @GetMapping("/desk/shipments")
    @Transactional(readOnly = true)
    public String shipments(@RequestParam(required = false) ShipmentStatus status, @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page, Model model) {
        Warehouse w = own();
        String query = q == null || q.isBlank() ? null : q.trim();
        model.addAttribute("w", w);
        model.addAttribute("page", shipments.search(status, w.getId(), query, PageRequest.of(Math.max(0, page), 50)));
        model.addAttribute("status", status);
        model.addAttribute("q", query);
        model.addAttribute("statuses", ShipmentStatus.values());
        return "desk/shipments";
    }

    @GetMapping("/desk/book")
    public String bookForm(Model model) {
        Warehouse w = own();
        model.addAttribute("warehouses", List.of(w));
        model.addAttribute("desk", true);
        model.addAttribute("defaultDeadline", fmt.inputValue(clock.now().plusSeconds(6 * 3600).truncatedTo(ChronoUnit.HOURS)));
        return "shipments/new";
    }

    @PostMapping("/desk/book")
    public String book(@RequestParam String recipientName, @RequestParam(required = false) String recipientPhone,
                       @RequestParam String destName, @RequestParam String destAddress, @RequestParam String destPin,
                       @RequestParam double destLat, @RequestParam double destLng, @RequestParam int weightKg,
                       @RequestParam String deadline, RedirectAttributes ra) {
        Warehouse w = own();
        Shipment s = shipmentService.create(new ShipmentService.NewShipment(w.getId(), recipientName, recipientPhone,
            destName, destAddress, destPin, destLat, destLng, weightKg,
            LocalDateTime.parse(deadline).atZone(fmt.zone()).toInstant()));
        ra.addFlashAttribute("ok", "Parcel " + s.getTrackingNo() + " booked. It is waiting for planning.");
        return "redirect:/desk";
    }

    // ------------------------------------------------------------------ helpers

    private Warehouse own() {
        long id = scope.ownWarehouse();
        return warehouses.findById(id).orElseThrow(() -> new NotFoundException("Warehouse", id));
    }

    private Shipment mine(long shipmentId) {
        Shipment s = shipments.findById(shipmentId).orElseThrow(() -> new NotFoundException("Shipment", shipmentId));
        scope.require(s.getOriginWarehouse().getId(), "Shipment", shipmentId);
        s.getOriginWarehouse().getCity();
        return s;
    }

    private static void touchTrip(Shipment s) {
        if (s.getTrip() != null) {
            s.getTrip().getCode();
            s.getTrip().getDriver().getFullName();
            s.getTrip().getDriver().getPhone();
        }
    }

    /** Only redirect back to our own pages. */
    static String safeBack(String back) {
        return back != null && back.startsWith("/") && !back.startsWith("//") ? back : "/desk";
    }
}
