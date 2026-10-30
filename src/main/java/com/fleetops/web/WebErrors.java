package com.fleetops.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;

import com.fleetops.common.web.BusinessRuleException;

/**
 * A refused business rule is not an error page: forms go back where they came from with the
 * reason shown, and API calls get a 409 with a message the screen can display.
 */
@ControllerAdvice(basePackages = "com.fleetops")
public class WebErrors {

    @ExceptionHandler({BusinessRuleException.class, OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public Object handle(RuntimeException e, HttpServletRequest request) {
        String message = message(e);
        if (request.getRequestURI().startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message));
        }
        RequestContextUtils.getOutputFlashMap(request).put("error", message);
        String referer = request.getHeader("Referer");
        String target = referer != null && referer.contains("//" + request.getServerName()) ? referer : "/";
        return new ModelAndView("redirect:" + target);
    }

    private static String message(RuntimeException e) {
        if (e instanceof OptimisticLockingFailureException) {
            return "Someone else changed this at the same time. Reload and try again.";
        }
        if (e instanceof DataIntegrityViolationException dive) {
            String detail = String.valueOf(dive.getMostSpecificCause().getMessage());
            if (detail.contains("ux_trips_live_vehicle")) {
                return "That vehicle is already on another live trip.";
            }
            if (detail.contains("ux_trips_live_driver")) {
                return "That driver is already on another live trip.";
            }
            return "The change conflicts with existing data and was not saved.";
        }
        return e.getMessage();
    }
}
