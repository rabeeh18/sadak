package com.fleetops.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.fleetops.fleet.DriverRepository;
import com.fleetops.fleet.VehicleRepository;
import com.fleetops.fleet.WarehouseRepository;

import tools.jackson.databind.ObjectMapper;

@Controller
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final WarehouseRepository warehouses;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final ObjectMapper json;
    private final Fmt fmt;

    public AnalyticsController(AnalyticsService analytics, WarehouseRepository warehouses, VehicleRepository vehicles,
                               DriverRepository drivers, ObjectMapper json, Fmt fmt) {
        this.analytics = analytics;
        this.warehouses = warehouses;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.json = json;
        this.fmt = fmt;
    }

    private AnalyticsService.Filter filter(LocalDate from, LocalDate to, Long warehouse, Long vehicle, Long driver) {
        LocalDate t = to != null ? to : fmt.today();
        LocalDate f = from != null ? from : t.minusDays(27);
        if (f.isAfter(t)) {
            f = t;
        }
        return new AnalyticsService.Filter(f, t, warehouse, vehicle, driver);
    }

    @GetMapping("/analytics")
    public String page(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                       @RequestParam(required = false) Long warehouse, @RequestParam(required = false) Long vehicle,
                       @RequestParam(required = false) Long driver, Model model) {
        AnalyticsService.Filter f = filter(from, to, warehouse, vehicle, driver);
        List<Map<String, Object>> daily = analytics.daily(f);
        model.addAttribute("f", f);
        model.addAttribute("s", analytics.summary(f));
        model.addAttribute("byWarehouse", analytics.byWarehouse(f));
        model.addAttribute("byTown", analytics.byTown(f));
        model.addAttribute("byDriver", analytics.byDriver(f));
        List<Map<String, Object>> failures = analytics.failureReasons(f);
        model.addAttribute("failures", failures);
        // Embedded as a JSON data block; "</" is escaped so the data can never close the script element.
        model.addAttribute("chartData", json.writeValueAsString(Map.of(
            "daily", daily, "incidents", analytics.incidentsDaily(f), "failures", failures)).replace("</", "<\\/"));
        model.addAttribute("warehouses", warehouses.findAllByOrderByNameAsc());
        model.addAttribute("vehicles", vehicles.findAllByOrderByRegistrationAsc());
        model.addAttribute("drivers", drivers.findAllByOrderByFullNameAsc());
        model.addAttribute("query", "from=" + f.from() + "&to=" + f.to()
            + (warehouse != null ? "&warehouse=" + warehouse : "") + (vehicle != null ? "&vehicle=" + vehicle : "")
            + (driver != null ? "&driver=" + driver : ""));
        return "analytics";
    }

    @GetMapping("/analytics/export.csv")
    public void export(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                       @RequestParam(required = false) Long warehouse, @RequestParam(required = false) Long vehicle,
                       @RequestParam(required = false) Long driver, HttpServletResponse response) throws IOException {
        AnalyticsService.Filter f = filter(from, to, warehouse, vehicle, driver);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"trips-" + f.from() + "-to-" + f.to() + ".csv\"");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        List<Map<String, Object>> rows = analytics.tripsForExport(f);
        PrintWriter out = response.getWriter();
        if (rows.isEmpty()) {
            out.println("no trips in range");
            return;
        }
        out.println(String.join(",", rows.get(0).keySet()));
        for (Map<String, Object> row : rows) {
            out.println(row.values().stream().map(AnalyticsController::csv).reduce((a, b) -> a + "," + b).orElse(""));
        }
    }

    private static String csv(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
