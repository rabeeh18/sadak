package com.fleetops.fleet;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    List<Warehouse> findAllByOrderByNameAsc();

    List<Warehouse> findByActiveTrueOrderByNameAsc();

    Optional<Warehouse> findByCode(String code);

    boolean existsByCode(String code);
}
