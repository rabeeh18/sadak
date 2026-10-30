package com.fleetops.common.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

class GeometryTest {

    private static final GeoPoint LUDHIANA = new GeoPoint(30.9010, 75.8573);
    private static final GeoPoint MOGA = new GeoPoint(30.8165, 75.1717);

    @Test
    void haversineMatchesKnownDistance() {
        // Ludhiana to Moga is about 66 km as the crow flies.
        assertThat(LUDHIANA.distanceTo(MOGA) / 1000).isCloseTo(66.1, within(1.0));
    }

    @Test
    void polylineRoundTrips() {
        List<GeoPoint> points = List.of(LUDHIANA, new GeoPoint(30.85, 75.5), MOGA);
        List<GeoPoint> decoded = Polyline.decode(Polyline.encode(points));
        assertThat(decoded).hasSize(3);
        for (int i = 0; i < points.size(); i++) {
            assertThat(decoded.get(i).lat()).isCloseTo(points.get(i).lat(), within(1e-5));
            assertThat(decoded.get(i).lng()).isCloseTo(points.get(i).lng(), within(1e-5));
        }
    }

    @Test
    void decodesTheReferenceExample() {
        // Example from the encoded polyline algorithm documentation.
        List<GeoPoint> p = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
        assertThat(p.get(0).lat()).isCloseTo(38.5, within(1e-5));
        assertThat(p.get(2).lng()).isCloseTo(-126.453, within(1e-5));
    }

    @Test
    void projectsPositionOntoRoute() {
        RouteLine line = new RouteLine(List.of(LUDHIANA, MOGA));
        GeoPoint halfway = line.pointAt(line.length() / 2);
        RouteLine.Projection onRoute = line.project(halfway, 0);
        assertThat(onRoute.distanceAlong()).isCloseTo(line.length() / 2, within(5.0));
        assertThat(onRoute.offsetMeters()).isLessThan(5);

        GeoPoint offRoad = halfway.offset(900, LUDHIANA.bearingTo(MOGA) + 90);
        RouteLine.Projection off = line.project(offRoad, 0);
        assertThat(off.offsetMeters()).isCloseTo(900, within(30.0));
        assertThat(off.distanceAlong()).isCloseTo(line.length() / 2, within(50.0));
    }

    @Test
    void forwardSearchDoesNotSnapBackOnRoadsThatDoubleBack() {
        GeoPoint a = new GeoPoint(30.0, 75.0);
        GeoPoint b = new GeoPoint(30.0, 75.1);
        GeoPoint c = new GeoPoint(30.001, 75.0); // road returns alongside itself
        RouteLine line = new RouteLine(List.of(a, b, c));
        GeoPoint nearEnd = line.pointAt(line.length() - 500);
        RouteLine.Projection p = line.project(nearEnd, line.length() - 1500);
        assertThat(p.distanceAlong()).isGreaterThan(line.length() / 2);
    }

    @Test
    void gridReferencesFollowTheSheetGrid() {
        assertThat(GridReference.of(GridReference.NORTH - 0.01, GridReference.WEST + 0.01)).isEqualTo("A1");
        assertThat(GridReference.of(MOGA)).isEqualTo("F7");
        assertThat(GridReference.of(10, 10)).isEqualTo("—");
    }
}
