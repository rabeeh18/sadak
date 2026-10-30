package com.fleetops.security;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    @Query("select distinct u from UserAccount u join u.roles r where r in :roles and u.enabled = true")
    List<UserAccount> findEnabledWithAnyRole(List<Role> roles);

    List<UserAccount> findAllByOrderByUsernameAsc();
}
