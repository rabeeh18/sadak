package com.fleetops.incident;

import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fleetops.fleet.Vehicle;
import com.fleetops.risk.SlaBreachEvent;
import com.fleetops.risk.SlaBreachRepository;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.trip.RiskBand;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripEvents;
import com.fleetops.trip.TripRepository;
import com.fleetops.trip.TripStop;
import com.fleetops.trip.TripStopRepository;

/**
 * Turns reported trip events into incidents and SLA breach records. Monitoring finds conditions;
 * this handles facts someone reported: a breakdown, a failed delivery, a late hand-over.
 */
@Component
public class TripEventIncidents {

    private final IncidentService incidents;
    private final TripRepository trips;
    private final TripStopRepository stops;
    private final ShipmentRepository shipments;
    private final SlaBreachRepository breaches;

    public TripEventIncidents(IncidentService incidents, TripRepository trips, TripStopRepository stops,
                              ShipmentRepository shipments, SlaBreachRepository breaches) {
        this.incidents = incidents;
        this.trips = trips;
        this.stops = stops;
        this.shipments = shipments;
        this.breaches = breaches;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBreakdown(TripEvents.VehicleBrokeDown e) {
        Trip trip = trips.findWithResourcesById(e.tripId()).orElseThrow();
        Vehicle v = trip.getVehicle();
        long remaining = trip.getStops().stream().filter(s -> !s.getStatus().isDone()).count();
        incidents.detect(new IncidentService.Detection(e.tripId(), IncidentType.BREAKDOWN, RiskBand.CRITICAL,
            v.getRegistration() + " broke down on " + trip.getCode() + " with " + remaining + " stops remaining",
            e.note(), null, v));
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(TripEvents.StopFailed e) {
        TripStop stop = stops.findById(e.stopId()).orElseThrow();
        Trip trip = stop.getTrip();
        List<Shipment> failed = shipments.findByStopId(e.stopId());
        for (Shipment s : failed) {
            if (!breaches.existsByShipmentIdAndKind(s.getId(), SlaBreachEvent.Kind.FAILED)) {
                breaches.save(new SlaBreachEvent(s.getId(), trip.getId(), s.getDeadline(), stop.getActualDeparture(),
                    (int) Math.max(0, Duration.between(s.getDeadline(), stop.getActualDeparture()).toMinutes()),
                    SlaBreachEvent.Kind.FAILED));
            }
        }
        incidents.detect(new IncidentService.Detection(e.tripId(), IncidentType.FAILED_DELIVERY, RiskBand.HIGH,
            "Delivery failed at " + stop.getName() + " (" + trip.getCode() + " stop " + stop.getSeq() + ")",
            e.reason(), failed.isEmpty() ? null : failed.get(0), null));
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(TripEvents.StopCompleted e) {
        TripStop stop = stops.findById(e.stopId()).orElseThrow();
        for (Shipment s : shipments.findByStopId(e.stopId())) {
            if (s.getDeliveredAt() != null && s.getDeliveredAt().isAfter(s.getDeadline())
                    && !breaches.existsByShipmentIdAndKind(s.getId(), SlaBreachEvent.Kind.LATE_DELIVERY)) {
                breaches.save(new SlaBreachEvent(s.getId(), stop.getTrip().getId(), s.getDeadline(), s.getDeliveredAt(),
                    (int) Duration.between(s.getDeadline(), s.getDeliveredAt()).toMinutes(), SlaBreachEvent.Kind.LATE_DELIVERY));
            }
        }
    }

    @TransactionalEventListener
    public void onTripEnded(TripEvents.TripEnded e) {
        incidents.closeTransientForTrip(e.tripId());
    }
}
