package com.fleetops.web;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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

import com.fleetops.common.web.NotFoundException;
import com.fleetops.config.AppMode;
import com.fleetops.security.Role;
import com.fleetops.security.SecurityConfig;
import com.fleetops.security.UserAccountRepository;

@Controller
public class HomeController {

    private static final Set<String> DEMO_USERS = Set.of("dispatcher", "admin", "driver", "warehouse");

    private final AppMode mode;
    private final UserAccountRepository users;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public HomeController(AppMode mode, UserAccountRepository users) {
        this.mode = mode;
        this.users = users;
    }

    /** Everyone lands on the screen for the role an admin gave them. */
    @GetMapping("/")
    public String home(Authentication auth) {
        boolean driver = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(Role.DRIVER.authority()));
        boolean dispatch = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(Role.DISPATCHER.authority())
            || a.getAuthority().equals(Role.ADMIN.authority()));
        boolean warehouse = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(Role.WAREHOUSE.authority()));
        return dispatch ? "redirect:/tower" : warehouse ? "redirect:/desk" : driver ? "redirect:/driver" : "redirect:/track";
    }

    /**
     * Opens on "Track a parcel" unless the URL or the outcome of a sign-in says staff. When the
     * server decides, the tab is fixed; otherwise login.js may restore the visitor's last choice.
     */
    @GetMapping("/login")
    public String login(@RequestParam(required = false) String staff, @RequestParam(required = false) String demo,
                        @RequestParam(required = false) String error, @RequestParam(required = false) String passwordSet,
                        Model model) {
        boolean signIn = staff != null || error != null || passwordSet != null || model.containsAttribute("prefillEmail");
        boolean pickRole = mode.demo() && demo != null;
        // Demo mode opens on "Try the demo"; production on tracking.
        model.addAttribute("tab", signIn ? "staff" : pickRole || mode.demo() ? "demo" : "track");
        model.addAttribute("tabFixed", signIn || pickRole);
        return "login";
    }

    /** Demo mode only: choose which seeded user to sign in as. */
    @GetMapping("/demo")
    public String demo() {
        if (!mode.demo()) {
            throw new NotFoundException("Page", "/demo");
        }
        return "demo";
    }

    /** Demo mode only: one-click sign-in as a seeded user, for showing the project. */
    @PostMapping("/demo-login")
    public String demoLogin(@RequestParam("as") String username, HttpServletRequest request, HttpServletResponse response) {
        if (!mode.demo() || !DEMO_USERS.contains(username)) {
            throw new NotFoundException("Page", "/demo-login");
        }
        UserDetails user = users.findByUsername(username).map(SecurityConfig::toUserDetails)
            .orElseThrow(() -> new NotFoundException("User", username));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        request.getSession(true);
        request.changeSessionId();
        contextRepository.saveContext(context, request, response);
        return "redirect:/";
    }
}
