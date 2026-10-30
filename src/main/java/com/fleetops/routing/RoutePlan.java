package com.fleetops.routing;

import java.util.List;

/**
 * A road route through ordered waypoints: geometry plus one leg per consecutive pair.
 * {@code estimated} is true when the routing provider was unavailable and straight lines were used.
 */
public record RoutePlan(String encodedGeometry, int distanceM, int durationS,
                        List<Integer> legDistancesM, List<Integer> legDurationsS, boolean estimated) {
}
