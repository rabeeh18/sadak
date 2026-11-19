package com.fleetops.web;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.fleetops.common.geo.Polyline;
import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.complaint.Complaint;
import com.fleetops.complaint.ComplaintCategory;
import com.fleetops.complaint.ComplaintRepository;
import com.fleetops.complaint.CustomerHelpService;
import com.fleetops.fleet.Warehouse;
import com.fleetops.history.StatusChange;
import com.fleetops.history.StatusHistoryService;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.shipment.ShipmentStatus;
import com.fleetops.tracking.LiveFleet;
import com.fleetops.tracking.LiveTrip;

/**
 * Anonymous shipment lookup for recipients. Requires both the tracking number and the delivery
 * PIN code, is rate-limited per client, and shows no personal details or exact vehicle position.
 * The same two values also let the customer leave a note for the driver or report a problem.
 */
@Controller
public class PublicTrackingController {

    private static final int LOOKUPS_PER_MINUTE = 10;
    /** The route is drawn with a gap this long either side of the vehicle, so the line doesn't give away where it is. */
    private static final double PRIVACY_GAP_M = 800;

    private final ShipmentRepository shipments;
    private final StatusHistoryService history;
    private final LiveFleet live;
    private final ComplaintRepository complaints;
    private final CustomerHelpService help;
    private final OperationalClock clock;
    private final Fmt fmt;
    private final TransactionTemplate readOnly;
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public PublicTrackingController(ShipmentRepository shipments, StatusHistoryService history, LiveFleet live,
                                    ComplaintRepository complaints, CustomerHelpService help, OperationalClock clock, Fmt fmt,
                                    PlatformTransactionManager txManager) {
        this.readOnly = new TransactionTemplate(txManager);
        this.readOnly.setReadOnly(true);
        this.shipments = shipments;
        this.history = history;
        this.live = live;
        this.complaints = complaints;
        this.help = help;
        this.clock = clock;
        this.fmt = fmt;
    }

    /** One step of the progress bar. {@code state} is done, now, todo or bad. */
    public record Step(String label, Instant at, String state) {
    }

    @GetMapping("/track")
    public String form(@RequestParam(name = "n", required = false) String trackingNo, Model model) {
        model.addAttribute("n", clean(trackingNo));
        model.addAttribute("pin", "");
        model.addAttribute("categories", ComplaintCategory.values());
        return "track";
    }

    @PostMapping("/track")
    public String lookup(@RequestParam("n") String trackingNo, @RequestParam("pin") String pin,
                         HttpServletRequest request, Model model) {
        Shipment s = find(trackingNo, pin, request, model);
        return s == null ? "track" : show(s.getId(), model);
    }

    @PostMapping("/track/note")
    public String note(@RequestParam("n") String trackingNo, @RequestParam("pin") String pin,
                       @RequestParam(defaultValue = "") String note, HttpServletRequest request, Model model) {
        Shipment s = find(trackingNo, pin, request, model);
        if (s == null) {
            return "track";
        }
        model.addAttribute("helpOpen", "note");
        try {
            help.leaveNote(s.getId(), note);
            model.addAttribute("helpOk", note.isBlank() ? "Note removed." : "Your note is with the driver. You can change it until delivery.");
        } catch (BusinessRuleException e) {
            model.addAttribute("helpError", e.getMessage());
            model.addAttribute("draftNote", note);
        }
        return show(s.getId(), model);
    }

    @PostMapping("/track/complaint")
    public String complaint(@RequestParam("n") String trackingNo, @RequestParam("pin") String pin,
                            @RequestParam(required = false) ComplaintCategory category,
                            @RequestParam(defaultValue = "") String message, @RequestParam(defaultValue = "") String phone,
                            HttpServletRequest request, Model model) {
        Shipment s = find(trackingNo, pin, request, model);
        if (s == null) {
            return "track";
        }
        model.addAttribute("helpOpen", "problem");
        try {
            Complaint c = help.file(s.getId(), category, message, phone);
            model.addAttribute("helpOk", "Complaint " + c.getCode() + " received. The " + s.getOriginWarehouse().getCity()
                + " desk will call you back" + (s.getOriginWarehouse().deskOpenAt(localTime()) ? " soon." : " when it opens."));
        } catch (BusinessRuleException e) {
            model.addAttribute("helpError", e.getMessage());
            model.addAttribute("draft", Map.of("category", category == null ? "" : category.name(), "message", message, "phone", phone));
        }
        return show(s.getId(), model);
    }

    // ------------------------------------------------------------------ page model

    private Shipment find(String trackingNo, String pin, HttpServletRequest request, Model model) {
        String n = clean(trackingNo);
        String p = pin == null ? "" : pin.trim();
        model.addAttribute("n", n);
        model.addAttribute("pin", p);
        model.addAttribute("categories", ComplaintCategory.values());
        if (!allow(request.getRemoteAddr())) {
            model.addAttribute("problem", "Too many lookups from this connection. Wait a minute and try again.");
            return null;
        }
        Shipment s = shipments.findWithWarehouseByTrackingNo(n).filter(x -> x.getDestPin().equals(p)).orElse(null);
        if (s == null) {
            // Same message whether the number or the PIN is wrong, so neither can be probed.
            model.addAttribute("problem", "We could not find a shipment with that tracking number and PIN code.");
        }
        return s;
    }

    private String show(long shipmentId, Model model) {
        return readOnly.execute(tx -> render(shipmentId, model));
    }

    private String render(long shipmentId, Model model) {
        Shipment s = shipments.findById(shipmentId).orElseThrow();
        Warehouse w = s.getOriginWarehouse();
        w.getCity(); // rendered after the transaction closes
        model.addAttribute("s", s);
        model.addAttribute("town", s.getDestName().split(" — ")[0]);
        model.addAttribute("warehouse", w);
        model.addAttribute("deskOpen", w.deskOpenAt(localTime()));
        model.addAttribute("deskHours", w.getDeskOpens() == null || w.getDeskCloses() == null ? null
            : hour(w.getDeskOpens()) + " – " + hour(w.getDeskCloses()));
        model.addAttribute("deskOpens", w.getDeskOpens() == null ? null : hour(w.getDeskOpens()));

        List<StatusChange> events = history.historyOf(StatusHistoryService.SHIPMENT, s.getId());
        model.addAttribute("steps", steps(s, events));

        Instant eta = s.getStop() != null && (s.getStatus() == ShipmentStatus.OUT_FOR_DELIVERY || s.getStatus() == ShipmentStatus.PLANNED)
            ? s.getStop().getEta() : null;
        if (s.getStatus() == ShipmentStatus.OUT_FOR_DELIVERY && s.getTrip() != null) {
            live.find(s.getTrip().getId()).ifPresent(lt -> mapOf(lt, s, model));
        }
        model.addAttribute("eta", eta);
        Instant compare = s.getStatus() == ShipmentStatus.DELIVERED ? s.getDeliveredAt() : eta;
        long late = compare == null ? 0 : Duration.between(s.getDeadline(), compare).toMinutes();
        model.addAttribute("lateMin", Math.max(0, late));
        if (eta != null) {
            model.addAttribute("etaIn", Duration.between(clock.now(), eta).toMinutes());
        }

        complaints.findFirstByShipmentIdOrderByCreatedAtDesc(s.getId()).ifPresent(c -> model.addAttribute("complaint", c));
        boolean active = s.getStatus() != ShipmentStatus.DELIVERED && s.getStatus() != ShipmentStatus.CANCELLED
            && s.getStatus() != ShipmentStatus.FAILED;
        model.addAttribute("canNote", active);
        model.addAttribute("noteMax", CustomerHelpService.NOTE_MAX);
        return "track";
    }

    /** The road still to go, starting a little past the vehicle so it doesn't give away its position; plus its rough position. */
    private void mapOf(LiveTrip lt, Shipment s, Model model) {
        synchronized (lt) {
            if (lt.position() == null) {
                return;
            }
            // Coarse position only: rounded to about a kilometre.
            model.addAttribute("vehicleLat", Math.round(lt.position().lat() * 100) / 100.0);
            model.addAttribute("vehicleLng", Math.round(lt.position().lng() * 100) / 100.0);
            List<LiveTrip.OpenStop> open = lt.openStops();
            model.addAttribute("stopsBefore", open.stream().takeWhile(o -> o.stopId != s.getStop().getId()).count());
            double target = open.stream().filter(o -> o.stopId == s.getStop().getId()).mapToDouble(o -> o.distanceAlong)
                .findFirst().orElse(lt.route.length());
            double at = lt.progress();
            if (target - at > PRIVACY_GAP_M) {
                model.addAttribute("todoLine", Polyline.encode(lt.route.between(at + PRIVACY_GAP_M, target)));
            }
        }
    }

    private List<Step> steps(Shipment s, List<StatusChange> events) {
        ShipmentStatus st = s.getStatus();
        int reached = switch (st) {
            case CREATED -> 0;
            case PLANNED -> 1;
            case OUT_FOR_DELIVERY -> 2;
            case DELIVERED, FAILED, CANCELLED -> 3;
        };
        List<Step> out = new ArrayList<>();
        out.add(new Step("Booked", s.getCreatedAt(), reached == 0 ? "now" : "done"));
        out.add(new Step("Planned", latest(events, "PLANNED"), reached == 1 ? "now" : reached > 1 && st != ShipmentStatus.CANCELLED ? "done" : "todo"));
        out.add(new Step("On the way", latest(events, "OUT_FOR_DELIVERY"), reached == 2 ? "now"
            : reached > 2 && st != ShipmentStatus.CANCELLED ? "done" : "todo"));
        switch (st) {
            case FAILED -> out.add(new Step("Not delivered", latest(events, "FAILED"), "bad"));
            case CANCELLED -> out.add(new Step("Cancelled", latest(events, "CANCELLED"), "bad"));
            default -> out.add(new Step("Delivered", s.getDeliveredAt(), st == ShipmentStatus.DELIVERED ? "done" : "todo"));
        }
        return out;
    }

    private static Instant latest(List<StatusChange> events, String to) {
        Instant at = null;
        for (StatusChange e : events) {
            if (to.equals(e.getToStatus()) && (at == null || e.getChangedAt().isAfter(at))) {
                at = e.getChangedAt();
            }
        }
        return at;
    }

    private LocalTime localTime() {
        return clock.now().atZone(fmt.zone()).toLocalTime();
    }

    /** 9 am, 7 pm, 9:30 am. */
    private static String hour(LocalTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return h + (t.getMinute() == 0 ? "" : String.format(":%02d", t.getMinute())) + (t.getHour() < 12 ? " am" : " pm");
    }

    private static String clean(String trackingNo) {
        return trackingNo == null ? "" : trackingNo.trim().toUpperCase();
    }

    private boolean allow(String client) {
        long now = System.currentTimeMillis();
        Deque<Long> window = attempts.computeIfAbsent(client, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) {
                window.pollFirst();
            }
            if (window.size() >= LOOKUPS_PER_MINUTE) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
