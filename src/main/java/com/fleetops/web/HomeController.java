package com.fleetops.web;

import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fleetops.common.web.NotFoundException;
import com.fleetops.config.AppMode;
import com.fleetops.demo.DemoDrivers;
import com.fleetops.demo.DemoReset;
import com.fleetops.demo.DemoWarehouses;
import com.fleetops.security.Role;
import com.fleetops.security.SecurityConfig;
import com.fleetops.security.UserAccount;
import com.fleetops.security.UserAccountRepository;

/** The sign-in page (pick a demo role, or track a parcel), demo sign-in and the demo reset. */
@Controller
public class HomeController {

    private static final Set<String> DEMO_USERS = Set.of("dispatcher", "warehouse");

    private final AppMode mode;
    private final UserAccountRepository users;
    private final ObjectProvider<DemoDrivers> demoDrivers;
    private final ObjectProvider<DemoReset> demoReset;
    private final ObjectProvider<DemoWarehouses> demoWarehouses;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public HomeController(AppMode mode, UserAccountRepository users, ObjectProvider<DemoDrivers> demoDrivers,
                          ObjectProvider<DemoReset> demoReset, ObjectProvider<DemoWarehouses> demoWarehouses) {
        this.demoWarehouses = demoWarehouses;
        this.mode = mode;
        this.users = users;
        this.demoDrivers = demoDrivers;
        this.demoReset = demoReset;
    }

    /** Everyone lands on the screen for their role. */
    @GetMapping("/")
    public String home(Authentication auth) {
        boolean dispatch = has(auth, Role.DISPATCHER);
        return dispatch ? "redirect:/tower" : has(auth, Role.WAREHOUSE) ? "redirect:/desk"
            : has(auth, Role.DRIVER) ? "redirect:/driver" : "redirect:/track";
    }

    /** Opens on "Try the demo"; login.js may restore the visitor's last tab unless the URL names one. */
    @GetMapping("/login")
    public String login(@RequestParam(required = false) String demo, @RequestParam(required = false) String track, Model model) {
        DemoDrivers picker = demoDrivers.getIfAvailable();
        boolean demoTab = picker != null && track == null;
        model.addAttribute("tab", demoTab ? "demo" : "track");
        model.addAttribute("tabFixed", demo != null || track != null);
        if (picker != null) {
            model.addAttribute("demoDrivers", picker.options());
            DemoWarehouses desks = demoWarehouses.getIfAvailable();
            model.addAttribute("demoWarehouses", desks == null ? List.of() : desks.options());
        }
        return "login";
    }

    /** Demo mode: one-click sign-in as the dispatcher, the Patiala warehouse desk, or any driver with a trip. */
    @PostMapping("/demo-login")
    public String demoLogin(@RequestParam("as") String as, @RequestParam(required = false) Long driverId,
                            @RequestParam(required = false) Long warehouseId,
                            HttpServletRequest request, HttpServletResponse response) {
        DemoDrivers picker = demoDrivers.getIfAvailable();
        if (!mode.demo() || picker == null) {
            throw new NotFoundException("Page", "/demo-login");
        }
        UserAccount account;
        if (as.equals("driver")) {
            Long id = driverId != null ? driverId : picker.manualDriverId();
            if (id == null) {
                throw new NotFoundException("Driver", "for the demo");
            }
            // Loaded again by id: the account comes back from the picker's own transaction as a lazy reference.
            long accountId = picker.accountFor(id).getId();
            account = users.findById(accountId).orElseThrow(() -> new NotFoundException("User", accountId));
        } else if (as.equals("warehouse") && warehouseId != null && demoWarehouses.getIfAvailable() != null) {
            long accountId = demoWarehouses.getIfAvailable().accountFor(warehouseId);
            account = users.findById(accountId).orElseThrow(() -> new NotFoundException("User", accountId));
        } else if (DEMO_USERS.contains(as)) {
            account = users.findByUsername(as).orElseThrow(() -> new NotFoundException("User", as));
        } else {
            throw new NotFoundException("Page", "/demo-login");
        }
        UserDetails user = SecurityConfig.toUserDetails(account);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        request.getSession(true);
        request.changeSessionId();
        contextRepository.saveContext(context, request, response);
        return "redirect:/";
    }

    /** Demo mode: throw everything away and start a fresh demo day. */
    @PostMapping("/demo-reset")
    public String resetDemo(HttpServletRequest request, RedirectAttributes ra) {
        DemoReset reset = demoReset.getIfAvailable();
        if (reset == null) {
            throw new NotFoundException("Page", "/demo-reset");
        }
        reset.reset();
        // Accounts were recreated, so start the visitor with a clean session.
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        ra.addFlashAttribute("resetDone", true);
        return "redirect:/login?demo";
    }

    private static boolean has(Authentication auth, Role role) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(role.authority()));
    }
}
