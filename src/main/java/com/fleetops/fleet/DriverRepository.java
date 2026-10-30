package com.fleetops.fleet;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    @EntityGraph(attributePaths = {"homeWarehouse", "user"})
    List<Driver> findAllByOrderByFullNameAsc();

    @EntityGraph(attributePaths = "homeWarehouse")
    List<Driver> findByStatusOrderByFullNameAsc(DriverStatus status);

    Optional<Driver> findByUserUsername(String username);

    boolean existsByLicenseNumber(String licenseNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Driver d where d.id = :id")
    Optional<Driver> lockById(Long id);
}
