package com.fleetops.web;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fleetops.complaint.Complaint;
import com.fleetops.complaint.ComplaintRepository;
import com.fleetops.complaint.CustomerHelpService;
import com.fleetops.fleet.Warehouse;
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.security.CurrentUser;
import com.fleetops.security.Role;
import com.fleetops.security.StaffScope;
import com.fleetops.security.UserAccount;
import com.fleetops.security.UserAccountRepository;
import com.fleetops.shipment.Shipment;

/**
 * Customer complaints. Warehouse staff see their own warehouse's; dispatchers and admins see all.
 * Anything outside someone's warehouse is "not found".
 */
@Controller
public class ComplaintsController {

    private final ComplaintRepository complaints;
    private final CustomerHelpService help;
    private final StaffScope scope;
    private final WarehouseRepository warehouses;
    private final UserAccountRepository users;

    public ComplaintsController(ComplaintRepository complaints, CustomerHelpService help, StaffScope scope,
                                WarehouseRepository warehouses, UserAccountRepository users) {
        this.complaints = complaints;
        this.help = help;
        this.scope = scope;
        this.warehouses = warehouses;
        this.users = users;
    }

    @GetMapping("/complaints")
    @Transactional(readOnly = true)
    public String list(Model model) {
        Long mine = scope.warehouse();
        List<Complaint> open = complaints.list(mine, EnumSet.of(Complaint.Status.NEW, Complaint.Status.IN_PROGRESS));
        List<Complaint> resolved = complaints.list(mine, EnumSet.of(Complaint.Status.RESOLVED)).stream().limit(30).toList();
        model.addAttribute("open", open);
        model.addAttribute("resolved", resolved);
        model.addAttribute("warehouseNames", warehouses.findAll().stream().collect(Collectors.toMap(Warehouse::getId, Warehouse::getName)));
        model.addAttribute("names", staffNames());
        return "complaints/list";
    }

    @GetMapping("/complaints/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable long id, Model model) {
        Complaint c = visible(id);
        Shipment s = c.getShipment();
        s.getOriginWarehouse().getName();
        if (s.getTrip() != null) {
            s.getTrip().getCode();
            s.getTrip().getVehicle().getRegistration();
            s.getTrip().getDriver().getFullName();
        }
        if (s.getStop() != null) {
            s.getStop().getSeq();
        }
        model.addAttribute("c", c);
        model.addAttribute("s", s);
        model.addAttribute("names", staffNames());
        model.addAttribute("deskUser", CurrentUser.hasRole(Role.WAREHOUSE) && scope.warehouse() != null);
        return "complaints/detail";
    }

    @PostMapping("/complaints/{id}/take")
    public String take(@PathVariable long id, @RequestParam(required = false) String back, RedirectAttributes ra) {
        visible(id);
        Complaint c = help.take(id);
        ra.addFlashAttribute("ok", c.getCode() + " is yours. Call the customer back.");
        return redirect(back, id);
    }

    @PostMapping("/complaints/{id}/resolve")
    public String resolve(@PathVariable long id, @RequestParam String reply, @RequestParam(required = false) String back,
                          RedirectAttributes ra) {
        visible(id);
        Complaint c = help.resolve(id, reply);
        ra.addFlashAttribute("ok", c.getCode() + " resolved. The customer sees your reply on the tracking page.");
        return redirect(back, id);
    }

    @PostMapping("/complaints/{id}/dispatch")
    public String toDispatch(@PathVariable long id, @RequestParam(required = false) String note,
                             @RequestParam(required = false) String back, RedirectAttributes ra) {
        visible(id);
        Complaint c = help.sendToDispatch(id, note);
        ra.addFlashAttribute("ok", c.getCode() + " sent to dispatch. The control tower has been notified.");
        return redirect(back, id);
    }

    private Complaint visible(long id) {
        Complaint c = help.get(id);
        scope.require(c.getWarehouseId(), "Complaint", id);
        return c;
    }

    private Map<String, String> staffNames() {
        return users.findAll().stream().collect(Collectors.toMap(UserAccount::getUsername, UserAccount::getFullName));
    }

    private static String redirect(String back, long id) {
        return "redirect:" + (back == null || back.isBlank() ? "/complaints/" + id : DeskController.safeBack(back));
    }
}
