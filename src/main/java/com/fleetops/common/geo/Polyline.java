package com.fleetops.common.geo;

import java.util.ArrayList;
import java.util.List;

/** Google encoded polyline format, precision 5 (what OSRM returns with geometries=polyline). */
public final class Polyline {

    private Polyline() {
    }

    public static List<GeoPoint> decode(String encoded) {
        List<GeoPoint> points = new ArrayList<>();
        int index = 0;
        int lat = 0;
        int lng = 0;
        while (index < encoded.length()) {
            int[] result = decodeValue(encoded, index);
            lat += result[0];
            index = result[1];
            result = decodeValue(encoded, index);
            lng += result[0];
            index = result[1];
            points.add(new GeoPoint(lat / 1e5, lng / 1e5));
        }
        return points;
    }

    public static String encode(List<GeoPoint> points) {
        StringBuilder sb = new StringBuilder();
        long prevLat = 0;
        long prevLng = 0;
        for (GeoPoint p : points) {
            long lat = Math.round(p.lat() * 1e5);
            long lng = Math.round(p.lng() * 1e5);
            encodeValue(lat - prevLat, sb);
            encodeValue(lng - prevLng, sb);
            prevLat = lat;
            prevLng = lng;
        }
        return sb.toString();
    }

    private static int[] decodeValue(String encoded, int start) {
        int result = 0;
        int shift = 0;
        int index = start;
        int b;
        do {
            b = encoded.charAt(index++) - 63;
            result |= (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        int value = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
        return new int[] {value, index};
    }

    private static void encodeValue(long value, StringBuilder sb) {
        long v = value < 0 ? ~(value << 1) : (value << 1);
        while (v >= 0x20) {
            sb.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        sb.append((char) (v + 63));
    }
}
