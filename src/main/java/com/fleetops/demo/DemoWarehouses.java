package com.fleetops.demo;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.common.web.NotFoundException;
import com.fleetops.complaint.Complaint;
import com.fleetops.complaint.ComplaintRepository;
import com.fleetops.fleet.Warehouse;
import com.fleetops.fleet.WarehouseRepository;
import com.fleetops.security.Role;
import com.fleetops.security.UserAccount;
import com.fleetops.security.UserAccountRepository;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripRepository;
import com.fleetops.trip.TripStatus;

/**
 * Demo mode: lets a visitor open any warehouse's desk. Each desk still sees only its own
 * warehouse. A desk account is created the first time someone picks that warehouse.
 */
@Service
@Profile("demo")
public class DemoWarehouses {

    /** One warehouse in the picker. */
    public record Option(long warehouseId, String name, String city, String code, long trucksOut, long openComplaints) {
    }

    /** Who answers each desk, by warehouse code. Patiala's (Harleen Kaur) is seeded by {@link DemoDesk}. */
    private static final Map<String, String> STAFF = Map.of(
        "LDH", "Navneet Sandhu", "JUC", "Ramandeep Kaur", "ASR", "Gurdeep Aulakh",
        "PTA", "Harleen Kaur", "BTI", "Manjit Brar", "MOH", "Sukhjinder Gill");

    private final WarehouseRepository warehouses;
    private final TripRepository trips;
    private final ComplaintRepository complaints;
    private final UserAccountRepository users;

    public DemoWarehouses(WarehouseRepository warehouses, TripRepository trips, ComplaintRepository complaints,
                          UserAccountRepository users) {
        this.warehouses = warehouses;
        this.trips = trips;
        this.complaints = complaints;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<Option> options() {
        Map<Long, Long> out = trips.findByStatusInOrderByPlannedStartAsc(List.of(TripStatus.IN_PROGRESS)).stream()
            .collect(Collectors.groupingBy((Trip t) -> t.getWarehouse().getId(), Collectors.counting()));
        return warehouses.findByActiveTrueOrderByNameAsc().stream()
            .map(w -> new Option(w.getId(), w.getName(), w.getCity(), w.getCode(), out.getOrDefault(w.getId(), 0L),
                complaints.countByWarehouseIdAndStatusNot(w.getId(), Complaint.Status.RESOLVED)))
            .toList();
    }

    /** The warehouse's desk account, created the first time it is picked. Same password as the other demo accounts. */
    @Transactional
    public long accountFor(long warehouseId) {
        Warehouse w = warehouses.findById(warehouseId).orElseThrow(() -> new NotFoundException("Warehouse", warehouseId));
        return users.findFirstByWarehouseIdOrderByIdAsc(w.getId()).map(UserAccount::getId).orElseGet(() -> {
            String hash = users.findByUsername("dispatcher").map(UserAccount::getPasswordHash)
                .orElseThrow(() -> new NotFoundException("User", "dispatcher"));
            String code = w.getCode().toLowerCase(java.util.Locale.ROOT);
            UserAccount u = users.save(new UserAccount("warehouse-" + code, "warehouse-" + code + "@sadak.example", hash,
                STAFF.getOrDefault(w.getCode(), w.getCity() + " desk"), EnumSet.of(Role.WAREHOUSE)));
            u.setWarehouseId(w.getId());
            return u.getId();
        });
    }
}
