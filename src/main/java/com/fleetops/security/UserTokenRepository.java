package com.fleetops.security;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    @EntityGraph(attributePaths = "user")
    Optional<UserToken> findByTokenHash(String tokenHash);

    Optional<UserToken> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    /** Retires every unused link of a user, so only the newest one (or none) works. */
    @Modifying
    @Query("update UserToken t set t.usedAt = :at where t.user.id = :userId and t.usedAt is null")
    int retireAll(Long userId, Instant at);
}
