package com.fleetops.routing;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fleet.routing")
public record RoutingProperties(String osrmBaseUrl, Duration timeout, double fallbackSpeedKph, double fallbackRoadFactor) {
}
