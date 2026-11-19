package com.fleetops.complaint;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    @EntityGraph(attributePaths = {"shipment", "shipment.originWarehouse"})
    Optional<Complaint> findWithShipmentById(Long id);

    Optional<Complaint> findFirstByShipmentIdOrderByCreatedAtDesc(Long shipmentId);

    boolean existsByShipmentIdAndStatusNot(Long shipmentId, Complaint.Status status);

    /** Open complaints first, then the most recent resolved ones; {@code warehouseId} null means all. */
    @EntityGraph(attributePaths = {"shipment", "shipment.originWarehouse", "shipment.trip"})
    @Query("""
        select c from Complaint c
        where (:warehouseId is null or c.warehouseId = :warehouseId)
          and c.status in :statuses
        order by c.createdAt desc
        """)
    List<Complaint> list(Long warehouseId, Collection<Complaint.Status> statuses);

    long countByStatusNot(Complaint.Status status);

    long countByWarehouseIdAndStatusNot(Long warehouseId, Complaint.Status status);

    @Query(value = "select nextval('complaint_code_seq')", nativeQuery = true)
    long nextCode();
}
