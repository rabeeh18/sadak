package com.fleetops.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.fleetops.config.AppMode;

/**
 * Production only: on the very first start, creates the first admin from FLEET_ADMIN_EMAIL and
 * FLEET_ADMIN_PASSWORD. Every other account is invited by an admin. The demo seeds its own users.
 */
@Component
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppMode mode;
    private final AccountService accounts;
    private final UserAccountRepository users;
    private final String email;
    private final String password;
    private final String name;

    public AdminBootstrap(AppMode mode, AccountService accounts, UserAccountRepository users,
                          @Value("${fleet.admin.email:}") String email,
                          @Value("${fleet.admin.password:}") String password,
                          @Value("${fleet.admin.name:Administrator}") String name) {
        this.mode = mode;
        this.accounts = accounts;
        this.users = users;
        this.email = email;
        this.password = password;
        this.name = name;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createFirstAdmin() {
        if (mode.demo() || users.count() > 0) {
            return;
        }
        if (email.isBlank() || password.isBlank()) {
            log.warn("No accounts exist yet. Set FLEET_ADMIN_EMAIL and FLEET_ADMIN_PASSWORD and restart to create the first admin.");
            return;
        }
        if (accounts.bootstrapAdmin(email, password, name)) {
            log.info("First admin {} created. Remove FLEET_ADMIN_PASSWORD from the environment now.", email);
        }
    }
}
