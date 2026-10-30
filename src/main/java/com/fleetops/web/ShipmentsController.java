package com.fleetops.web;

import java.time.LocalDateTime;

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
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.history.StatusHistoryService;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.shipment.ShipmentService;
import com.fleetops.shipment.ShipmentStatus;

@Controller
public class ShipmentsController {

    private final ShipmentRepository shipments;
    private final ShipmentService shipmentService;
    private final WarehouseRepository warehouses;
    private final StatusHistoryService history;
    private final OperationalClock clock;
    private final Fmt fmt;

    public ShipmentsController(ShipmentRepository shipments, ShipmentService shipmentService, WarehouseRepository warehouses,
                               StatusHistoryService history, OperationalClock clock, Fmt fmt) {
        this.shipments = shipments;
        this.shipmentService = shipmentService;
        this.warehouses = warehouses;
        this.history = history;
        this.clock = clock;
        this.fmt = fmt;
    }

    @GetMapping("/shipments")
    @Transactional(readOnly = true)
    public String list(@RequestParam(required = false) ShipmentStatus status, @RequestParam(required = false) Long warehouse,
                       @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, Model model) {
        String query = q == null || q.isBlank() ? null : q.trim();
        model.addAttribute("page", shipments.search(status, warehouse, query, PageRequest.of(Math.max(0, page), 50)));
        model.addAttribute("status", status);
        model.addAttribute("warehouse", warehouse);
        model.addAttribute("q", query);
        model.addAttribute("statuses", ShipmentStatus.values());
        model.addAttribute("warehouses", warehouses.findAllByOrderByNameAsc());
        model.addAttribute("waiting", shipments.countByStatus(ShipmentStatus.CREATED));
        return "shipments/list";
    }

    @GetMapping("/shipments/new")
    public String form(Model model) {
        model.addAttribute("warehouses", warehouses.findByActiveTrueOrderByNameAsc());
        model.addAttribute("defaultDeadline", fmt.inputValue(clock.now().plusSeconds(6 * 3600).truncatedTo(java.time.temporal.ChronoUnit.HOURS)));
        return "shipments/new";
    }

    @PostMapping("/shipments")
    public String create(@RequestParam long warehouseId, @RequestParam String recipientName,
                         @RequestParam(required = false) String recipientPhone, @RequestParam String destName,
                         @RequestParam String destAddress, @RequestParam String destPin,
                         @RequestParam double destLat, @RequestParam double destLng, @RequestParam int weightKg,
                         @RequestParam String deadline, RedirectAttributes ra) {
        Shipment s = shipmentService.create(new ShipmentService.NewShipment(warehouseId, recipientName, recipientPhone,
            destName, destAddress, destPin, destLat, destLng, weightKg,
            LocalDateTime.parse(deadline).atZone(fmt.zone()).toInstant()));
        ra.addFlashAttribute("ok", "Shipment " + s.getTrackingNo() + " booked. It is waiting for planning.");
        return "redirect:/shipments/" + s.getId();
    }

    @GetMapping("/shipments/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable long id, Model model) {
        Shipment s = shipmentService.get(id);
        s.getOriginWarehouse().getName();
        if (s.getTrip() != null) {
            s.getTrip().getCode();
            s.getTrip().getVehicle().getRegistration();
        }
        if (s.getStop() != null) {
            s.getStop().getName();
        }
        model.addAttribute("s", s);
        model.addAttribute("history", history.historyOf(StatusHistoryService.SHIPMENT, id));
        return "shipments/detail";
    }

    @PostMapping("/shipments/{id}/requeue")
    public String requeue(@PathVariable long id, RedirectAttributes ra) {
        shipmentService.requeue(id, "Re-queued for another attempt");
        ra.addFlashAttribute("ok", "Back in the planning queue.");
        return "redirect:/shipments/" + id;
    }

    @PostMapping("/shipments/{id}/cancel")
    public String cancel(@PathVariable long id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        shipmentService.cancel(id, reason);
        ra.addFlashAttribute("ok", "Shipment cancelled.");
        return "redirect:/shipments/" + id;
    }
}
