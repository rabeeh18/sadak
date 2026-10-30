package com.fleetops.common.geo;

import java.util.List;

/**
 * A route geometry with cumulative distances, supporting the two questions tracking asks:
 * where is the point N metres along the route, and how far along (and how far off) is a position.
 */
public final class RouteLine {

    private final List<GeoPoint> points;
    private final double[] cumulative;

    public RouteLine(List<GeoPoint> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("A route needs at least two points");
        }
        this.points = List.copyOf(points);
        this.cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            cumulative[i] = cumulative[i - 1] + points.get(i - 1).distanceTo(points.get(i));
        }
    }

    public static RouteLine fromEncoded(String encoded) {
        return new RouteLine(Polyline.decode(encoded));
    }

    public double length() {
        return cumulative[cumulative.length - 1];
    }

    public List<GeoPoint> points() {
        return points;
    }

    public GeoPoint pointAt(double distance) {
        return locate(distance).point();
    }

    /** Point and heading at a distance along the route, clamped to the ends. */
    public Located locate(double distance) {
        double d = Math.max(0, Math.min(length(), distance));
        int i = segmentIndexFor(d);
        GeoPoint a = points.get(i);
        GeoPoint b = points.get(i + 1);
        double segLen = cumulative[i + 1] - cumulative[i];
        double t = segLen <= 0 ? 0 : (d - cumulative[i]) / segLen;
        GeoPoint p = new GeoPoint(a.lat() + (b.lat() - a.lat()) * t, a.lng() + (b.lng() - a.lng()) * t);
        return new Located(p, a.bearingTo(b), i);
    }

    /**
     * Projects a position onto the route. When {@code fromDistance} is given the search favours
     * segments at or after it, so a vehicle on a road that doubles back is not snapped backwards.
     */
    public Projection project(GeoPoint position, double fromDistance) {
        int startSeg = fromDistance > 0 ? Math.max(0, segmentIndexFor(fromDistance) - 2) : 0;
        Projection best = null;
        for (int i = startSeg; i < points.size() - 1; i++) {
            Projection candidate = projectOnSegment(position, i);
            if (best == null || candidate.offsetMeters() < best.offsetMeters()) {
                best = candidate;
            }
        }
        if (startSeg > 0 && best.offsetMeters() > 300) {
            // Fall back to a full search when the forward window gives a poor match.
            for (int i = 0; i < startSeg; i++) {
                Projection candidate = projectOnSegment(position, i);
                if (candidate.offsetMeters() < best.offsetMeters()) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private Projection projectOnSegment(GeoPoint p, int i) {
        GeoPoint a = points.get(i);
        GeoPoint b = points.get(i + 1);
        // Local equirectangular projection around a: accurate enough at segment scale.
        double cosLat = Math.cos(Math.toRadians(a.lat()));
        double ax = 0;
        double ay = 0;
        double bx = (b.lng() - a.lng()) * cosLat;
        double by = b.lat() - a.lat();
        double px = (p.lng() - a.lng()) * cosLat;
        double py = p.lat() - a.lat();
        double segSq = bx * bx + by * by;
        double t = segSq == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * bx + (py - ay) * by) / segSq));
        GeoPoint onLine = new GeoPoint(a.lat() + (b.lat() - a.lat()) * t, a.lng() + (b.lng() - a.lng()) * t);
        double along = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * t;
        return new Projection(along, p.distanceTo(onLine), onLine);
    }

    private int segmentIndexFor(double d) {
        int lo = 0;
        int hi = cumulative.length - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] <= d) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return Math.min(lo, points.size() - 2);
    }

    public record Located(GeoPoint point, double bearing, int segment) {
    }

    public record Projection(double distanceAlong, double offsetMeters, GeoPoint snapped) {
    }
}
