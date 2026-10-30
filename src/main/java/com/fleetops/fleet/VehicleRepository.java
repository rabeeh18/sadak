package com.fleetops.fleet;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    @EntityGraph(attributePaths = "homeWarehouse")
    List<Vehicle> findAllByOrderByRegistrationAsc();

    @EntityGraph(attributePaths = "homeWarehouse")
    List<Vehicle> findByStatusOrderByRegistrationAsc(VehicleStatus status);

    @EntityGraph(attributePaths = "homeWarehouse")
    List<Vehicle> findByStatusInOrderByRegistrationAsc(Collection<VehicleStatus> statuses);

    Optional<Vehicle> findByRegistration(String registration);

    boolean existsByRegistration(String registration);

    /** Row lock used by allocation so two dispatchers cannot take the same vehicle. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vehicle v where v.id = :id")
    Optional<Vehicle> lockById(Long id);

    long countByStatus(VehicleStatus status);

    @Query("select count(v) from Vehicle v where v.status <> com.fleetops.fleet.VehicleStatus.RETIRED")
    long countInService();
}
