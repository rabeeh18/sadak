package com.fleetops.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.fleetops.IntegrationTestSupport;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.Warehouse;
import com.fleetops.security.Role;
import com.fleetops.shipment.Shipment;
import com.fleetops.trip.Trip;
import com.fleetops.trip.TripService;

@AutoConfigureMockMvc
class AccessControlIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired TripService trips;

    @Test
    void anonymousVisitorsAreSentToSignIn() throws Exception {
        mvc.perform(get("/tower")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/tower/snapshot")).andExpect(status().is3xxRedirection());
    }

    @Test
    void driversCannotOpenDispatchOrAdminScreens() throws Exception {
        mvc.perform(get("/tower").with(user("d").roles("DRIVER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/settings").with(user("d").roles("DRIVER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/sim/pause").with(user("d").roles("DRIVER")).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void dispatchersCannotChangeAdminSettings() throws Exception {
        mvc.perform(get("/admin/settings").with(user("x").roles("DISPATCHER"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/rules/BREAKDOWN").param("points", "0").with(user("x").roles("DISPATCHER")).with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void aDriverCanOnlyActOnTheirOwnTrip() throws Exception {
        Warehouse w = warehouse();
        Driver mine = driver(w, "Mine");
        mine.setUser(account("mine", Role.DRIVER));
        drivers.save(mine);
        Driver other = driver(w, "Other");
        other.setUser(account("other", Role.DRIVER));
        drivers.save(other);
        Trip trip = trips.plan(new TripService.PlanRequest(w.getId(), List.of(shipment(w, 30.8, 75.2, 100).getId()),
            vehicle(w, 2500).getId(), mine.getId(), clock.now(), false));

        mvc.perform(post("/driver/trips/" + trip.getId() + "/start").with(user("other").roles("DRIVER")).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/driver/trips/" + trip.getId() + "/start").with(user("mine").roles("DRIVER")).with(csrf()))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void publicTrackingNeedsTheMatchingPinCode() throws Exception {
        Warehouse w = warehouse();
        Shipment s = shipment(w, 30.8, 75.2, 100);
        mvc.perform(post("/track").param("n", s.getTrackingNo()).param("pin", "000000").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("could not find")));
        mvc.perform(post("/track").param("n", s.getTrackingNo()).param("pin", "142001").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Awaiting planning")));
    }

    @Test
    void stateChangesWithoutACsrfTokenAreRejected() throws Exception {
        mvc.perform(post("/api/sim/pause").with(user("x").roles("DISPATCHER"))).andExpect(status().isForbidden());
    }
}
