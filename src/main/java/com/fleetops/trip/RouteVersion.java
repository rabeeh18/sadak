package com.fleetops.trip;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A planned road route for a trip. A new version is created whenever the route changes
 * (reassignment, recalculation); completed stops keep the version they were served on.
 */
@Entity
@Table(name = "route_versions")
public class RouteVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    @Column(name = "version_no")
    private int versionNo;

    private boolean active = true;

    @Column(nullable = false)
    private String geometry;

    @Column(name = "distance_m")
    private int distanceM;

    @Column(name = "duration_s")
    private int durationS;

    @Column(name = "leg_distances_m", nullable = false)
    private String legDistancesM;

    @Column(name = "leg_durations_s", nullable = false)
    private String legDurationsS;

    @Column(name = "start_offset_m")
    private int startOffsetM;

    private boolean estimated;

    @Column(nullable = false)
    private String reason;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RouteVersion() {
    }

    public RouteVersion(Trip trip, int versionNo, String geometry, int distanceM, int durationS,
                        List<Integer> legDistances, List<Integer> legDurations, boolean estimated,
                        String reason, String createdBy, Instant createdAt) {
        this.trip = trip;
        this.versionNo = versionNo;
        this.geometry = geometry;
        this.distanceM = distanceM;
        this.durationS = durationS;
        this.legDistancesM = join(legDistances);
        this.legDurationsS = join(legDurations);
        this.estimated = estimated;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    private static String join(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static List<Integer> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).map(Integer::valueOf).toList();
    }

    public void deactivate() {
        this.active = false;
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public boolean isActive() {
        return active;
    }

    public String getGeometry() {
        return geometry;
    }

    public int getDistanceM() {
        return distanceM;
    }

    public int getDurationS() {
        return durationS;
    }

    public List<Integer> legDistances() {
        return split(legDistancesM);
    }

    public List<Integer> legDurations() {
        return split(legDurationsS);
    }

    public int getStartOffsetM() {
        return startOffsetM;
    }

    public boolean isEstimated() {
        return estimated;
    }

    public String getReason() {
        return reason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
