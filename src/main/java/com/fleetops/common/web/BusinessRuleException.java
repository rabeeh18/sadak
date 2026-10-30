package com.fleetops.common.web;

/**
 * A request that is well formed but not allowed in the current state, e.g. assigning a vehicle
 * that is already on a trip. Rendered as HTTP 409 with the message shown to the user.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
