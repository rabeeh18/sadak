package com.fleetops.trip;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TripRepository extends JpaRepository<Trip, Long> {

    @EntityGraph(attributePaths = {"vehicle", "driver", "warehouse"})
    List<Trip> findByStatusInOrderByPlannedStartAsc(Collection<TripStatus> statuses);

    @EntityGraph(attributePaths = {"vehicle", "driver", "warehouse"})
    Optional<Trip> findWithResourcesById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> lockById(Long id);

    @EntityGraph(attributePaths = {"vehicle", "driver", "warehouse"})
    Optional<Trip> findFirstByDriverIdAndStatusInOrderByPlannedStartAsc(Long driverId, Collection<TripStatus> statuses);

    @EntityGraph(attributePaths = {"vehicle", "driver", "warehouse"})
    Optional<Trip> findFirstByVehicleIdAndStatusIn(Long vehicleId, Collection<TripStatus> statuses);

    Optional<Trip> findByCode(String code);

    @EntityGraph(attributePaths = {"vehicle", "driver", "warehouse"})
    @Query("""
        select t from Trip t
        where (:status is null or t.status = :status)
          and (:warehouseId is null or t.warehouse.id = :warehouseId)
          and (:vehicleId is null or t.vehicle.id = :vehicleId)
          and (:driverId is null or t.driver.id = :driverId)
          and t.plannedStart >= :from and t.plannedStart < :to
        order by t.plannedStart desc
        """)
    Page<Trip> search(TripStatus status, Long warehouseId, Long vehicleId, Long driverId,
                      Instant from, Instant to, Pageable pageable);

    long countByStatus(TripStatus status);

    @Query(value = "select nextval('trip_code_seq')", nativeQuery = true)
    long nextCodeNumber();
}
