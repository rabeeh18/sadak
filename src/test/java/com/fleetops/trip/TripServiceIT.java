package com.fleetops.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fleetops.IntegrationTestSupport;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.DriverStatus;
import com.fleetops.fleet.Vehicle;
import com.fleetops.fleet.VehicleStatus;
import com.fleetops.fleet.Warehouse;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentStatus;

class TripServiceIT extends IntegrationTestSupport {

    @Autowired TripService trips;
    @Autowired TripRepository tripRepository;
    @Autowired RouteVersionRepository routes;
    @Autowired TripAssignmentRepository assignments;
    @Autowired TripStopRepository stopRepository;

    @Test
    void planGroupsShipmentsByAddressAndReservesVehicleAndDriver() {
        Warehouse w = warehouse();
        Vehicle v = vehicle(w, 2500);
        Driver d = driver(w, "Manpreet Gill");
        Shipment a = shipment(w, 30.8165, 75.1717, 200);
        Shipment b = shipment(w, 30.8165, 75.1717, 150); // same address as a
        Shipment c = shipment(w, 30.7883, 75.4732, 100); // Jagraon, nearer the warehouse

        Trip trip = trips.plan(new TripService.PlanRequest(w.getId(), List.of(a.getId(), b.getId(), c.getId()),
            v.getId(), d.getId(), clock.now(), true));

        Trip saved = trips.get(trip.getId());
        assertThat(stopRepository.findByTripIdOrderBySeqAsc(trip.getId())).hasSize(2);
        assertThat(jdbc.queryForObject("select st.seq from shipments s join trip_stops st on st.id = s.stop_id where s.id = ?", Integer.class, c.getId())).isEqualTo(1); // nearest first
        assertThat(saved.getTotalWeightKg()).isEqualTo(450);
        assertThat(vehicles.findById(v.getId()).orElseThrow().getStatus()).isEqualTo(VehicleStatus.ON_TRIP);
        assertThat(drivers.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DriverStatus.ON_TRIP);
        assertThat(shipments.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.PLANNED);
        RouteVersion route = routes.findByTripIdAndActiveTrue(trip.getId()).orElseThrow();
        assertThat(route.isEstimated()).isTrue(); // tests never reach the public routing server
        assertThat(route.legDistances()).hasSize(2);
    }

    @Test
    void planRefusesLoadOverCapacity() {
        Warehouse w = warehouse();
        Vehicle small = vehicle(w, 300);
        Driver d = driver(w, "Harjit Kaur");
        Shipment big = shipment(w, 30.8, 75.2, 400);
        assertThatThrownBy(() -> trips.plan(new TripService.PlanRequest(w.getId(), List.of(big.getId()), small.getId(), d.getId(), clock.now(), true)))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("exceeds");
        assertThat(shipments.findById(big.getId()).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    void aVehicleCannotBeOnTwoLiveTrips() {
        Warehouse w = warehouse();
        Vehicle v = vehicle(w, 2500);
        Driver d1 = driver(w, "Driver One");
        Driver d2 = driver(w, "Driver Two");
        trips.plan(new TripService.PlanRequest(w.getId(), List.of(shipment(w, 30.8, 75.2, 100).getId()), v.getId(), d1.getId(), clock.now(), true));
        assertThatThrownBy(() -> trips.plan(new TripService.PlanRequest(w.getId(), List.of(shipment(w, 30.7, 75.3, 100).getId()),
                v.getId(), d2.getId(), clock.now(), true)))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("on trip");
    }

    @Test
    void deliveringEveryStopCompletesTheTripAndFreesResources() {
        Warehouse w = warehouse();
        Vehicle v = vehicle(w, 2500);
        Driver d = driver(w, "Kulwant Brar");
        Shipment s = shipment(w, 30.8, 75.2, 100);
        Trip trip = trips.plan(new TripService.PlanRequest(w.getId(), List.of(s.getId()), v.getId(), d.getId(), clock.now(), true));
        trips.start(trip.getId());
        long stopId = stopRepository.findByTripIdOrderBySeqAsc(trip.getId()).get(0).getId();

        trips.arrive(trip.getId(), stopId);
        trips.completeStop(trip.getId(), stopId, "Simran Brar", "Left at counter", null);

        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getStatus()).isEqualTo(TripStatus.COMPLETED);
        assertThat(shipments.findById(s.getId()).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(vehicles.findById(v.getId()).orElseThrow().getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
        assertThat(drivers.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DriverStatus.AVAILABLE);
    }

    @Test
    void reassignmentAfterBreakdownRoutesViaTheBreakdownSite() {
        Warehouse w = warehouse();
        Vehicle broken = vehicle(w, 2500);
        Vehicle spare = vehicle(w, 2500);
        Driver d1 = driver(w, "Original Driver");
        Driver d2 = driver(w, "Relief Driver");
        Shipment s1 = shipment(w, 30.7883, 75.4732, 100);
        Shipment s2 = shipment(w, 30.8165, 75.1717, 100);
        Trip trip = trips.plan(new TripService.PlanRequest(w.getId(), List.of(s1.getId(), s2.getId()), broken.getId(), d1.getId(), clock.now(), true));
        trips.start(trip.getId());
        List<TripStop> before = stopRepository.findByTripIdOrderBySeqAsc(trip.getId());
        trips.completeStop(trip.getId(), before.get(0).getId(), "Recipient", null, null);
        trips.arrive(trip.getId(), before.get(1).getId()); // breaks down while parked at the second stop

        trips.reportBreakdown(trip.getId(), "Clutch failure");
        trips.reassign(trip.getId(), spare.getId(), d2.getId(), "Breakdown");

        Trip after = trips.get(trip.getId());
        assertThat(after.getVehicle().getId()).isEqualTo(spare.getId());
        assertThat(after.getDriver().getId()).isEqualTo(d2.getId());
        List<TripStop> stops = stopRepository.findByTripIdOrderBySeqAsc(trip.getId());
        assertThat(stops).extracting(TripStop::getKind)
            .containsExactly(TripStop.Kind.DELIVERY, TripStop.Kind.TRANSFER, TripStop.Kind.DELIVERY);
        assertThat(stops.get(0).getStatus()).isEqualTo(StopStatus.COMPLETED); // completed work is preserved
        assertThat(stops.get(2).getStatus()).isEqualTo(StopStatus.PENDING);   // the new vehicle has not been there yet
        assertThat(routes.findByTripIdOrderByVersionNoAsc(trip.getId())).extracting(RouteVersion::getVersionNo).containsExactly(1, 2);
        assertThat(routes.findByTripIdAndActiveTrue(trip.getId()).orElseThrow().getVersionNo()).isEqualTo(2);
        assertThat(assignments.findByTripIdOrderByAssignedAtAsc(trip.getId())).hasSize(2);
        assertThat(vehicles.findById(broken.getId()).orElseThrow().getStatus()).isEqualTo(VehicleStatus.BROKEN_DOWN);
        assertThat(drivers.findById(d1.getId()).orElseThrow().getStatus()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'TRIP_REASSIGNED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void cancellingReturnsShipmentsToThePlanningQueue() {
        Warehouse w = warehouse();
        Vehicle v = vehicle(w, 2500);
        Driver d = driver(w, "Rajvir Sidhu");
        Shipment s = shipment(w, 30.8, 75.2, 100);
        Trip trip = trips.plan(new TripService.PlanRequest(w.getId(), List.of(s.getId()), v.getId(), d.getId(), clock.now(), true));
        trips.cancel(trip.getId(), "Customer postponed");
        Shipment after = shipments.findById(s.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(jdbc.queryForObject("select trip_id from shipments where id = ?", Long.class, s.getId())).isNull();
        assertThat(vehicles.findById(v.getId()).orElseThrow().getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
    }
}
