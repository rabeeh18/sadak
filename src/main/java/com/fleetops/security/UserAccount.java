package com.fleetops.security;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Internal handle recorded as the actor in audit rows; people sign in with their email. */
    @Column(nullable = false, unique = true)
    private String username;

    /** Stored lower-case; unique regardless of case. */
    @Column(nullable = false)
    private String email;

    /** Null while an invite is pending: the person has not chosen a password yet and cannot sign in. */
    @Column(name = "password_set_at")
    private Instant passwordSetAt;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private boolean enabled = true;

    /** Only for Warehouse accounts: the one warehouse they work at. */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Roles are stored by name through the user_roles join; see {@link RoleConverter}. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role_id")
    @Convert(converter = RoleConverter.class)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    protected UserAccount() {
    }

    /** An active account whose password is already set. */
    public UserAccount(String username, String email, String passwordHash, String fullName, Set<Role> roles) {
        this.username = username;
        this.email = normaliseEmail(email);
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.roles = EnumSet.copyOf(roles);
        this.passwordSetAt = createdAt;
    }

    /** An invited account: it cannot sign in until the person sets a password from their invite link. */
    public static UserAccount invited(String username, String email, String unusablePasswordHash, String fullName, Role role) {
        UserAccount u = new UserAccount(username, email, unusablePasswordHash, fullName, EnumSet.of(role));
        u.passwordSetAt = null;
        return u;
    }

    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public Instant getPasswordSetAt() {
        return passwordSetAt;
    }

    public boolean isInvitePending() {
        return passwordSetAt == null;
    }

    /** Sets a new password chosen by the person themselves (invite acceptance or reset). */
    public void choosePassword(String newHash, Instant at) {
        this.passwordHash = newHash;
        this.passwordSetAt = at;
    }

    /** The role shown in the interface: the most powerful one held. */
    public Role primaryRole() {
        return roles.contains(Role.ADMIN) ? Role.ADMIN : roles.contains(Role.DISPATCHER) ? Role.DISPATCHER
            : roles.contains(Role.WAREHOUSE) ? Role.WAREHOUSE : Role.DRIVER;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles.clear();
        this.roles.addAll(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }
}
