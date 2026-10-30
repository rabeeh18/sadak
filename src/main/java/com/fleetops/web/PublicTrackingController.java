package com.fleetops.web;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.fleetops.history.StatusChange;
import com.fleetops.history.StatusHistoryService;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.shipment.ShipmentStatus;
import com.fleetops.tracking.LiveFleet;

/**
 * Anonymous shipment lookup for recipients. Requires both the tracking number and the delivery
 * PIN code, is rate-limited per client, and shows no personal details or exact vehicle position.
 */
@Controller
public class PublicTrackingController {

    private static final int LOOKUPS_PER_MINUTE = 10;

    private final ShipmentRepository shipments;
    private final StatusHistoryService history;
    private final LiveFleet live;
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public PublicTrackingController(ShipmentRepository shipments, StatusHistoryService history, LiveFleet live) {
        this.shipments = shipments;
        this.history = history;
        this.live = live;
    }

    @GetMapping("/track")
    public String form(@RequestParam(name = "n", required = false) String trackingNo, Model model) {
        model.addAttribute("n", trackingNo == null ? "" : trackingNo.trim().toUpperCase());
        return "track";
    }

    @PostMapping("/track")
    @Transactional(readOnly = true)
    public String lookup(@RequestParam("n") String trackingNo, @RequestParam("pin") String pin,
                         HttpServletRequest request, Model model) {
        String n = trackingNo == null ? "" : trackingNo.trim().toUpperCase();
        model.addAttribute("n", n);
        if (!allow(request.getRemoteAddr())) {
            model.addAttribute("problem", "Too many lookups from this connection. Wait a minute and try again.");
            return "track";
        }
        Shipment s = shipments.findByTrackingNo(n).filter(x -> x.getDestPin().equals(pin == null ? "" : pin.trim())).orElse(null);
        if (s == null) {
            // Same message whether the number or the PIN is wrong, so neither can be probed.
            model.addAttribute("problem", "We could not find a shipment with that tracking number and PIN code.");
            return "track";
        }
        s.getOriginWarehouse().getCity(); // rendered after the transaction closes
        model.addAttribute("s", s);
        model.addAttribute("town", s.getDestName().split(" — ")[0]);
        List<StatusChange> events = history.historyOf(StatusHistoryService.SHIPMENT, s.getId());
        model.addAttribute("events", events);
        Instant eta = null;
        if (s.getStatus() == ShipmentStatus.OUT_FOR_DELIVERY && s.getStop() != null) {
            eta = s.getStop().getEta();
            if (s.getTrip() != null) {
                live.find(s.getTrip().getId()).ifPresent(lt -> {
                    synchronized (lt) {
                        if (lt.position() != null) {
                            // Coarse position only: rounded to about a kilometre.
                            model.addAttribute("vehicleLat", Math.round(lt.position().lat() * 100) / 100.0);
                            model.addAttribute("vehicleLng", Math.round(lt.position().lng() * 100) / 100.0);
                            model.addAttribute("stopsBefore", lt.openStops().stream()
                                .takeWhile(o -> o.stopId != s.getStop().getId()).count());
                        }
                    }
                });
            }
        }
        model.addAttribute("eta", eta);
        return "track";
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
