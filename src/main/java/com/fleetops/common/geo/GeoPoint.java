package com.fleetops.common.geo;

public record GeoPoint(double lat, double lng) {

    private static final double EARTH_RADIUS_M = 6_371_000;

    public double distanceTo(GeoPoint other) {
        return haversine(lat, lng, other.lat, other.lng);
    }

    public static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Initial bearing in degrees (0 = north, clockwise). */
    public double bearingTo(GeoPoint other) {
        double phi1 = Math.toRadians(lat);
        double phi2 = Math.toRadians(other.lat);
        double dLng = Math.toRadians(other.lng - lng);
        double y = Math.sin(dLng) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLng);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Point at a distance (m) and bearing (deg) from this one. */
    public GeoPoint offset(double distanceM, double bearingDeg) {
        double delta = distanceM / EARTH_RADIUS_M;
        double theta = Math.toRadians(bearingDeg);
        double phi1 = Math.toRadians(lat);
        double lambda1 = Math.toRadians(lng);
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(delta) + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta));
        double lambda2 = lambda1 + Math.atan2(Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
                Math.cos(delta) - Math.sin(phi1) * Math.sin(phi2));
        return new GeoPoint(Math.toDegrees(phi2), Math.toDegrees(lambda2));
    }
}
