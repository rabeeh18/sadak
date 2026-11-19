package com.fleetops.security;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Emails are stored lower-case, so callers pass {@link UserAccount#normaliseEmail(String)}. */
    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("select distinct u from UserAccount u join u.roles r where r in :roles and u.enabled = true")
    List<UserAccount> findEnabledWithAnyRole(List<Role> roles);

    /** Accounts holding the role that can actually sign in: enabled and with a password set. */
    @Query("select count(distinct u) from UserAccount u join u.roles r where r = :role"
        + " and u.enabled = true and u.passwordSetAt is not null")
    long countActiveWithRole(Role role);

    List<UserAccount> findAllByOrderByFullNameAsc();
}
