package com.fleetops.trip;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.fleetops.common.geo.GeoPoint;
import com.fleetops.common.web.BusinessRuleException;

@Entity
@Table(name = "trip_stops")
public class TripStop {

    public enum Kind { DELIVERY, TRANSFER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind = Kind.DELIVERY;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    private double lat;
    private double lng;

    @Column(name = "service_min")
    private int serviceMin;

    @Column(name = "planned_arrival")
    private Instant plannedArrival;

    private Instant eta;

    @Column(name = "actual_arrival")
    private Instant actualArrival;

    @Column(name = "actual_departure")
    private Instant actualDeparture;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StopStatus status = StopStatus.PENDING;

    @Column(name = "route_version_no")
    private int routeVersionNo = 1;

    @Column(name = "leg_index")
    private int legIndex;

    protected TripStop() {
    }

    public TripStop(Kind kind, String name, String address, GeoPoint location, int serviceMin) {
        this.kind = kind;
        this.name = name;
        this.address = address;
        this.lat = location.lat();
        this.lng = location.lng();
        this.serviceMin = serviceMin;
    }

    void attachTo(Trip trip, int seq) {
        this.trip = trip;
        this.seq = seq;
    }

    public void arrive(Instant at) {
        if (status != StopStatus.PENDING) {
            throw new BusinessRuleException("Stop " + seq + " is already " + status.label().toLowerCase());
        }
        status = StopStatus.ARRIVED;
        actualArrival = at;
    }

    public void finish(StopStatus outcome, Instant at) {
        if (!outcome.isDone()) {
            throw new IllegalArgumentException("Not a final stop status: " + outcome);
        }
        if (status.isDone()) {
            throw new BusinessRuleException("Stop " + seq + " is already " + status.label().toLowerCase());
        }
        if (actualArrival == null && outcome != StopStatus.SKIPPED) {
            actualArrival = at;
        }
        status = outcome;
        actualDeparture = at;
    }

    /** A different vehicle takes over: it has not been to this stop yet. */
    public void resetArrival() {
        if (status == StopStatus.ARRIVED) {
            status = StopStatus.PENDING;
            actualArrival = null;
        }
    }

    public GeoPoint location() {
        return new GeoPoint(lat, lng);
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public Kind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public int getServiceMin() {
        return serviceMin;
    }

    public Instant getPlannedArrival() {
        return plannedArrival;
    }

    public void setPlannedArrival(Instant plannedArrival) {
        this.plannedArrival = plannedArrival;
    }

    public Instant getEta() {
        return eta;
    }

    public void setEta(Instant eta) {
        this.eta = eta;
    }

    public Instant getActualArrival() {
        return actualArrival;
    }

    public Instant getActualDeparture() {
        return actualDeparture;
    }

    public StopStatus getStatus() {
        return status;
    }

    public int getRouteVersionNo() {
        return routeVersionNo;
    }

    public int getLegIndex() {
        return legIndex;
    }

    public void placeOnRoute(int routeVersionNo, int legIndex) {
        this.routeVersionNo = routeVersionNo;
        this.legIndex = legIndex;
    }

    /** For the seeder: writes a finished stop with past timestamps. */
    public void restoreHistory(StopStatus status, Instant arrival, Instant departure) {
        this.status = status;
        this.actualArrival = arrival;
        this.actualDeparture = departure;
    }
}
