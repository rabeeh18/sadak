package com.fleetops.demo;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetops.common.web.NotFoundException;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.DriverRepository;
import com.fleetops.security.Role;
import com.fleetops.security.UserAccount;
import com.fleetops.security.UserAccountRepository;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripRepository;
import com.fleetops.trip.TripStatus;

/**
 * Demo mode: lets a visitor be any driver who has a trip. Gurpreet Singh's trip is driven by
 * hand (nothing completes itself); the others are simulated, and the visitor can record their
 * stops too. A driver gets a demo account the first time someone picks them.
 */
@Service
@Profile("demo")
public class DemoDrivers {

    /** One driver in the picker. */
    public record Option(long driverId, String name, String city, String tripCode, String plate, String when, boolean manual) {
    }

    private final DriverRepository drivers;
    private final TripRepository trips;
    private final UserAccountRepository users;
    private final ZoneId zone;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    public DemoDrivers(DriverRepository drivers, TripRepository trips, UserAccountRepository users,
                       @Value("${fleet.display-zone}") String zone) {
        this.drivers = drivers;
        this.trips = trips;
        this.users = users;
        this.zone = ZoneId.of(zone);
    }

    /** The manual demo driver first, then everyone on the road, then those about to leave. */
    @Transactional(readOnly = true)
    public List<Option> options() {
        Long manualId = manualDriverId();
        Map<Long, Option> out = new LinkedHashMap<>();
        List<Trip> live = trips.findByStatusInOrderByPlannedStartAsc(List.of(TripStatus.IN_PROGRESS, TripStatus.PLANNED));
        live.stream().filter(t -> t.getDriver().getId().equals(manualId)).findFirst().ifPresent(t -> out.put(manualId, option(t, true)));
        if (manualId != null && !out.containsKey(manualId)) {
            drivers.findById(manualId).ifPresent(d -> out.put(manualId,
                new Option(d.getId(), d.getFullName(), d.getHomeWarehouse().getCity(), null, null, "No trip right now", true)));
        }
        List<Trip> ordered = new ArrayList<>(live);
        ordered.sort((a, b) -> Boolean.compare(a.getStatus() != TripStatus.IN_PROGRESS, b.getStatus() != TripStatus.IN_PROGRESS));
        for (Trip t : ordered) {
            out.putIfAbsent(t.getDriver().getId(), option(t, false));
        }
        return List.copyOf(out.values());
    }

    /** The driver's account, created the first time they are picked. Same password as the other demo accounts. */
    @Transactional
    public UserAccount accountFor(long driverId) {
        Driver d = drivers.findById(driverId).orElseThrow(() -> new NotFoundException("Driver", driverId));
        if (d.getUser() != null) {
            return d.getUser();
        }
        String hash = users.findByUsername("dispatcher").map(UserAccount::getPasswordHash)
            .orElseThrow(() -> new NotFoundException("User", "dispatcher"));
        UserAccount u = users.save(new UserAccount("driver-" + d.getId(), "driver" + d.getId() + "@sadak.example", hash,
            d.getFullName(), EnumSet.of(Role.DRIVER)));
        d.setUser(u);
        return u;
    }

    /** Gurpreet Singh: the driver whose trip nothing completes by itself. */
    @Transactional(readOnly = true)
    public Long manualDriverId() {
        return drivers.findByUserUsername(DemoSeeder.DEMO_DRIVER_USERNAME).map(Driver::getId).orElse(null);
    }

    private Option option(Trip t, boolean manual) {
        Instant at = t.getStatus() == TripStatus.IN_PROGRESS ? null : t.getPlannedStart();
        String when = at == null ? "On the road" : "Leaves " + TIME.format(at.atZone(zone));
        return new Option(t.getDriver().getId(), t.getDriver().getFullName(), t.getWarehouse().getCity(), t.getCode(),
            t.getVehicle().getRegistration(), when, manual);
    }
}
