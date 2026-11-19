package com.fleetops.shipment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    @EntityGraph(attributePaths = "originWarehouse")
    Optional<Shipment> findWithWarehouseByTrackingNo(String trackingNo);

    @EntityGraph(attributePaths = "originWarehouse")
    List<Shipment> findByOriginWarehouseIdAndStatusOrderByDeadlineAsc(Long warehouseId, ShipmentStatus status);

    List<Shipment> findByTripIdOrderByIdAsc(Long tripId);

    List<Shipment> findByStopId(Long stopId);

    List<Shipment> findByTripIdInAndStatusIn(Collection<Long> tripIds, Collection<ShipmentStatus> statuses);

    List<Shipment> findByStopIdIn(Collection<Long> stopIds);

    @EntityGraph(attributePaths = {"originWarehouse", "trip"})
    @Query("""
        select s from Shipment s
        where (:status is null or s.status = :status)
          and (:warehouseId is null or s.originWarehouse.id = :warehouseId)
          and (cast(:q as String) is null
               or lower(s.trackingNo) like lower(concat('%', cast(:q as String), '%'))
               or lower(s.recipientName) like lower(concat('%', cast(:q as String), '%'))
               or lower(s.destName) like lower(concat('%', cast(:q as String), '%')))
        order by s.deadline desc
        """)
    Page<Shipment> search(ShipmentStatus status, Long warehouseId, String q, Pageable pageable);

    long countByStatus(ShipmentStatus status);

    @EntityGraph(attributePaths = "originWarehouse")
    List<Shipment> findByStatus(ShipmentStatus status);

    /** Parcels still to be delivered from a warehouse that carry a note from the customer. */
    @EntityGraph(attributePaths = {"trip"})
    @Query("""
        select s from Shipment s
        where s.originWarehouse.id = :warehouseId and s.customerNote is not null
          and s.status in (com.fleetops.shipment.ShipmentStatus.CREATED, com.fleetops.shipment.ShipmentStatus.PLANNED,
                           com.fleetops.shipment.ShipmentStatus.OUT_FOR_DELIVERY)
        order by s.customerNoteAt desc
        """)
    List<Shipment> openNotes(Long warehouseId);

    @Query(value = "select nextval('tracking_no_seq')", nativeQuery = true)
    long nextTrackingNumber();
}
