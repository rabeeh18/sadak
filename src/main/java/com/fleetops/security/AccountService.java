package com.fleetops.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.audit.AuditFields;
import com.fleetops.audit.AuditService;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.common.web.NotFoundException;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.DriverRepository;
import com.fleetops.fleet.Warehouse;
import com.fleetops.fleet.WarehouseRepository;

/**
 * Accounts are invite-only. An admin invites a person with a role; the person chooses their own
 * password from a one-time link. Links expire on real time, not the simulated clock.
 */
@Service
public class AccountService {

    public static final Duration INVITE_VALID = Duration.ofHours(48);
    public static final Duration RESET_VALID = Duration.ofHours(2);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    public static final int MIN_PASSWORD = 10;

    private final UserAccountRepository users;
    private final UserTokenRepository tokens;
    private final DriverRepository drivers;
    private final WarehouseRepository warehouses;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();

    public AccountService(UserAccountRepository users, UserTokenRepository tokens, DriverRepository drivers,
                          WarehouseRepository warehouses, PasswordEncoder encoder, AuditService audit,
                          @Value("${fleet.base-url}") String baseUrl) {
        this.users = users;
        this.tokens = tokens;
        this.drivers = drivers;
        this.warehouses = warehouses;
        this.encoder = encoder;
        this.audit = audit;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** A one-time link for the admin to pass on to the person (email is not part of Sadak). */
    public record Link(String url, String email) {
    }

    // ------------------------------------------------------------------ admin actions

    @Transactional
    public Link invite(String fullName, String email, Role role, Long driverId) {
        return invite(fullName, email, role, driverId, null);
    }

    /** Drivers are linked to their driver record, Warehouse staff to their warehouse. */
    @Transactional
    public Link invite(String fullName, String email, Role role, Long driverId, Long warehouseId) {
        String name = fullName == null ? "" : fullName.trim();
        String mail = UserAccount.normaliseEmail(email);
        if (name.isEmpty()) {
            throw new BusinessRuleException("Enter the person's full name");
        }
        if (mail == null || !EMAIL.matcher(mail).matches() || mail.length() > 254) {
            throw new BusinessRuleException("Enter a valid email address");
        }
        if (users.existsByEmail(mail)) {
            throw new BusinessRuleException(mail + " already has an account");
        }
        Driver driver = null;
        if (role == Role.DRIVER) {
            if (driverId == null) {
                throw new BusinessRuleException("Pick the driver record this account belongs to");
            }
            driver = drivers.findById(driverId).orElseThrow(() -> new NotFoundException("Driver", driverId));
            if (driver.getUser() != null) {
                throw new BusinessRuleException(driver.getFullName() + " already has an account");
            }
        } else if (driverId != null) {
            throw new BusinessRuleException("Only driver accounts are linked to a driver record");
        }
        Warehouse warehouse = null;
        if (role == Role.WAREHOUSE) {
            if (warehouseId == null) {
                throw new BusinessRuleException("Pick the warehouse this person works at");
            }
            warehouse = warehouses.findById(warehouseId).orElseThrow(() -> new NotFoundException("Warehouse", warehouseId));
        } else if (warehouseId != null) {
            throw new BusinessRuleException("Only Warehouse accounts belong to a warehouse");
        }

        UserAccount u = users.save(UserAccount.invited(uniqueUsername(mail), mail, encoder.encode(secret()), name, role));
        if (driver != null) {
            driver.setUser(u);
        }
        if (warehouse != null) {
            u.setWarehouseId(warehouse.getId());
        }
        audit.record("USER_INVITED", "USER", u.getId(), name + " (" + mail + ") invited as " + role.label()
                + (warehouse == null ? "" : " at " + warehouse.getName()),
            null, AuditFields.of("email", mail, "role", role, "driver", driver == null ? null : driver.getFullName(),
                "warehouse", warehouse == null ? null : warehouse.getCode()));
        return issue(u, UserToken.Purpose.INVITE);
    }

    @Transactional
    public Link resendInvite(long userId) {
        UserAccount u = user(userId);
        if (!u.isInvitePending()) {
            throw new BusinessRuleException(u.getFullName() + " has already accepted their invite");
        }
        if (!u.isEnabled()) {
            throw new BusinessRuleException("Enable " + u.getFullName() + "'s account first");
        }
        audit.record("USER_INVITE_RESENT", "USER", u.getId(), "Invite re-sent to " + u.getEmail());
        return issue(u, UserToken.Purpose.INVITE);
    }

    /** For when email is not set up, or the person cannot find the email: the admin passes the link on. */
    @Transactional
    public Link resetLink(long userId) {
        UserAccount u = user(userId);
        if (u.isInvitePending()) {
            return resendInvite(userId);
        }
        if (!u.isEnabled()) {
            throw new BusinessRuleException("Enable " + u.getFullName() + "'s account first");
        }
        audit.record("PASSWORD_RESET_ISSUED", "USER", u.getId(), "Password reset link created for " + u.getEmail());
        return issue(u, UserToken.Purpose.RESET);
    }

    @Transactional
    public void changeRole(long userId, Role role) {
        UserAccount u = user(userId);
        Role before = u.primaryRole();
        if (before == role) {
            return;
        }
        if (u.getUsername().equals(CurrentUser.username())) {
            throw new BusinessRuleException("You cannot change your own role. Ask another admin.");
        }
        if (role == Role.DRIVER) {
            throw new BusinessRuleException("Driver accounts belong to a driver record: invite the person as a driver instead");
        }
        if (role == Role.WAREHOUSE) {
            throw new BusinessRuleException("Warehouse accounts belong to a warehouse: invite the person as Warehouse instead");
        }
        if (before == Role.WAREHOUSE) {
            u.setWarehouseId(null);
        }
        if (before == Role.ADMIN && isLastAdmin(u)) {
            throw new BusinessRuleException(u.getFullName() + " is the only admin. Make someone else an admin first.");
        }
        if (before == Role.DRIVER) {
            drivers.findByUserUsername(u.getUsername()).ifPresent(d -> d.setUser(null));
        }
        u.setRoles(EnumSet.of(role));
        audit.record("USER_ROLE_CHANGED", "USER", u.getId(), u.getFullName() + " is now " + role.label(),
            AuditFields.of("role", before), AuditFields.of("role", role));
    }

    @Transactional
    public UserAccount toggleEnabled(long userId) {
        UserAccount u = user(userId);
        if (u.getUsername().equals(CurrentUser.username())) {
            throw new BusinessRuleException("You cannot disable your own account");
        }
        if (u.isEnabled() && u.primaryRole() == Role.ADMIN && isLastAdmin(u)) {
            throw new BusinessRuleException(u.getFullName() + " is the only admin and cannot be disabled");
        }
        u.setEnabled(!u.isEnabled());
        if (!u.isEnabled()) {
            tokens.retireAll(u.getId(), Instant.now());
        }
        audit.record(u.isEnabled() ? "USER_ENABLED" : "USER_DISABLED", "USER", userId,
            u.getFullName() + (u.isEnabled() ? " enabled" : " disabled"),
            AuditFields.of("enabled", !u.isEnabled()), AuditFields.of("enabled", u.isEnabled()));
        return u;
    }

    /** Creates the first admin of an empty installation. Does nothing once any account exists. */
    @Transactional
    public boolean bootstrapAdmin(String email, String password, String fullName) {
        if (users.count() > 0) {
            return false;
        }
        String mail = UserAccount.normaliseEmail(email);
        if (!EMAIL.matcher(mail).matches()) {
            throw new IllegalStateException("FLEET_ADMIN_EMAIL is not a valid email address");
        }
        checkPassword(password, password);
        UserAccount u = users.save(new UserAccount(uniqueUsername(mail), mail, encoder.encode(password), fullName,
            EnumSet.of(Role.ADMIN)));
        audit.record("USER_CREATED", "USER", u.getId(), "First admin " + mail + " created at installation");
        return true;
    }

    // ------------------------------------------------------------------ the person's own actions

    @Transactional(readOnly = true)
    public Optional<UserToken> usableToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHash(hash(token))
            .filter(t -> t.usableAt(Instant.now()) && t.getUser().isEnabled());
    }

    @Transactional
    public UserAccount choosePassword(String token, String password, String confirm) {
        UserToken t = usableToken(token).orElseThrow(() ->
            new BusinessRuleException("This link has expired or was already used. Ask your admin for a new one."));
        checkPassword(password, confirm);
        UserAccount u = t.getUser();
        Instant now = Instant.now();
        boolean invite = u.isInvitePending();
        u.choosePassword(encoder.encode(password), now);
        tokens.retireAll(u.getId(), now);
        audit.record(invite ? "INVITE_ACCEPTED" : "PASSWORD_RESET", "USER", u.getId(),
            u.getFullName() + (invite ? " accepted their invite" : " reset their password"));
        return u;
    }

    // ------------------------------------------------------------------ helpers

    private boolean isLastAdmin(UserAccount u) {
        boolean counted = u.isEnabled() && !u.isInvitePending();
        return counted && users.countActiveWithRole(Role.ADMIN) <= 1;
    }

    private static void checkPassword(String password, String confirm) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new BusinessRuleException("Passwords need at least " + MIN_PASSWORD + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessRuleException("Passwords can be at most 72 characters");
        }
        if (!password.equals(confirm)) {
            throw new BusinessRuleException("The two passwords do not match");
        }
    }

    private Link issue(UserAccount u, UserToken.Purpose purpose) {
        Instant now = Instant.now();
        tokens.retireAll(u.getId(), now);
        String token = secret();
        Duration valid = purpose == UserToken.Purpose.INVITE ? INVITE_VALID : RESET_VALID;
        tokens.save(new UserToken(u, purpose, hash(token), now, now.plus(valid)));
        String url = baseUrl + "/account/set-password?token=" + token;
        return new Link(url, u.getEmail());
    }

    private String uniqueUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-z0-9._-]", "");
        if (base.length() < 3) {
            base = (base + "user").substring(0, Math.max(3, base.length()));
        }
        base = base.substring(0, Math.min(base.length(), 36));
        String candidate = base;
        for (int n = 2; users.existsByUsername(candidate); n++) {
            candidate = base + n;
        }
        return candidate;
    }

    private String secret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private UserAccount user(long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
    }
}
