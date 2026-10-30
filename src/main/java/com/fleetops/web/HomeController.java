package com.fleetops.web;

import java.util.Arrays;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.fleetops.common.web.NotFoundException;
import com.fleetops.security.Role;

@Controller
public class HomeController {

    private static final Set<String> DEMO_USERS = Set.of("dispatcher", "admin", "driver");

    private final Environment env;
    private final UserDetailsService userDetails;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public HomeController(Environment env, UserDetailsService userDetails) {
        this.env = env;
        this.userDetails = userDetails;
    }

    @GetMapping("/")
    public String home(Authentication auth) {
        boolean driver = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(Role.DRIVER.authority()));
        boolean dispatch = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(Role.DISPATCHER.authority())
            || a.getAuthority().equals(Role.ADMIN.authority()));
        return dispatch ? "redirect:/tower" : driver ? "redirect:/driver" : "redirect:/track";
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("demo", isDemo());
        return "login";
    }

    /** Demo profile only: one-click sign-in as a seeded demo user, for portfolio visitors. */
    @PostMapping("/demo-login")
    public String demoLogin(@RequestParam("as") String username, HttpServletRequest request, HttpServletResponse response) {
        if (!isDemo() || !DEMO_USERS.contains(username)) {
            throw new NotFoundException("Page", "/demo-login");
        }
        UserDetails user = userDetails.loadUserByUsername(username);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        request.getSession(true);
        request.changeSessionId();
        contextRepository.saveContext(context, request, response);
        return "redirect:/";
    }

    private boolean isDemo() {
        return Arrays.asList(env.getActiveProfiles()).contains("demo")
            || (env.getActiveProfiles().length == 0 && Arrays.asList(env.getDefaultProfiles()).contains("demo"));
    }
}
