package com.fleetops.web;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fleetops.common.time.OperationalClock;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.DriverRepository;
import com.fleetops.fleet.DriverStatus;
import com.fleetops.fleet.Vehicle;
import com.fleetops.fleet.VehicleRepository;
import com.fleetops.fleet.VehicleStatus;
import com.fleetops.fleet.Warehouse;
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.shipment.ShipmentStatus;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripService;

/** Builds a trip from shipments waiting at a warehouse. */
@Controller
public class PlanningController {

    private final WarehouseRepository warehouses;
    private final ShipmentRepository shipments;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final TripService trips;
    private final OperationalClock clock;
    private final Fmt fmt;

    public PlanningController(WarehouseRepository warehouses, ShipmentRepository shipments, VehicleRepository vehicles,
                              DriverRepository drivers, TripService trips, OperationalClock clock, Fmt fmt) {
        this.warehouses = warehouses;
        this.shipments = shipments;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.trips = trips;
        this.clock = clock;
        this.fmt = fmt;
    }

    @GetMapping("/planning")
    @Transactional(readOnly = true)
    public String page(@RequestParam(required = false) Long warehouse, Model model) {
        List<Warehouse> all = warehouses.findByActiveTrueOrderByNameAsc();
        Warehouse selected = all.stream().filter(w -> w.getId().equals(warehouse)).findFirst()
            .orElse(all.stream().filter(w -> w.getCode().equals("LDH")).findFirst().orElse(all.isEmpty() ? null : all.get(0)));
        model.addAttribute("warehouses", all);
        model.addAttribute("selected", selected);
        if (selected != null) {
            model.addAttribute("shipments", shipments.findByOriginWarehouseIdAndStatusOrderByDeadlineAsc(selected.getId(), ShipmentStatus.CREATED));
            List<Vehicle> available = vehicles.findByStatusOrderByRegistrationAsc(VehicleStatus.AVAILABLE);
            model.addAttribute("vehicles", available.stream()
                .sorted((a, b) -> Boolean.compare(!a.getHomeWarehouse().getId().equals(selected.getId()), !b.getHomeWarehouse().getId().equals(selected.getId())))
                .toList());
            List<Driver> free = drivers.findByStatusOrderByFullNameAsc(DriverStatus.AVAILABLE);
            model.addAttribute("drivers", free.stream()
                .sorted((a, b) -> Boolean.compare(!a.getHomeWarehouse().getId().equals(selected.getId()), !b.getHomeWarehouse().getId().equals(selected.getId())))
                .toList());
            model.addAttribute("today", fmt.today());
        }
        model.addAttribute("defaultStart", fmt.inputValue(clock.now().plusSeconds(20 * 60).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)));
        return "planning";
    }

    @PostMapping("/planning")
    public String plan(@RequestParam long warehouseId, @RequestParam(name = "shipment", required = false) List<Long> shipmentIds,
                       @RequestParam long vehicleId, @RequestParam long driverId, @RequestParam String plannedStart,
                       @RequestParam(defaultValue = "false") boolean autoComplete,
                       @RequestParam(defaultValue = "false") boolean startNow, RedirectAttributes ra) {
        if (shipmentIds == null || shipmentIds.isEmpty()) {
            throw new BusinessRuleException("Tick at least one shipment to put on the trip");
        }
        Instant start = startNow ? clock.now() : LocalDateTime.parse(plannedStart).atZone(fmt.zone()).toInstant();
        Trip trip = trips.plan(new TripService.PlanRequest(warehouseId, shipmentIds, vehicleId, driverId, start, autoComplete));
        if (startNow) {
            trips.start(trip.getId());
        }
        ra.addFlashAttribute("ok", trip.getCode() + " planned with " + trip.getStops().size() + " stops"
            + (startNow ? " and started." : ". It departs at " + fmt.time(start) + "."));
        return "redirect:/trips/" + trip.getId();
    }
}
