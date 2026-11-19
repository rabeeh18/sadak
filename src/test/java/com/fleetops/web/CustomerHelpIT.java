package com.fleetops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fleetops.IntegrationTestSupport;
import com.fleetops.complaint.Complaint;
import com.fleetops.complaint.ComplaintRepository;
import com.fleetops.fleet.Warehouse;
import com.fleetops.security.Role;
import com.fleetops.security.UserAccount;
import com.fleetops.shipment.Shipment;

/** The tracking page's note and complaint, and the Warehouse role's view of them. */
@AutoConfigureMockMvc
class CustomerHelpIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired ComplaintRepository complaints;

    private static int client;

    /** Each request from its own address, so the tracking rate limit never gets in the way. */
    private static MockHttpServletRequestBuilder tracking(String path, Shipment s) {
        String ip = "10.0.0." + (++client % 250);
        return post(path).param("n", s.getTrackingNo()).param("pin", s.getDestPin()).with(csrf())
            .with(r -> { r.setRemoteAddr(ip); return r; });
    }

    @Test
    void customersLeaveANoteForTheDriverOnlyWithTheRightPin() throws Exception {
        Shipment s = shipment(warehouse(), 30.81, 75.17, 40);
        mvc.perform(tracking("/track/note", s).param("note", "Leave it with the chemist next door"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Your note is with the driver")));
        assertThat(shipments.findById(s.getId()).orElseThrow().getCustomerNote()).isEqualTo("Leave it with the chemist next door");

        mvc.perform(post("/track/note").param("n", s.getTrackingNo()).param("pin", "000000").param("note", "Changed").with(csrf()))
            .andExpect(content().string(containsString("could not find")));
        mvc.perform(tracking("/track/note", s).param("note", "x".repeat(201)))
            .andExpect(content().string(containsString("200 characters")));
        assertThat(shipments.findById(s.getId()).orElseThrow().getCustomerNote()).isEqualTo("Leave it with the chemist next door");
    }

    @Test
    void aComplaintGoesToTheDeskAndTheControlRoomOnceAtATime() throws Exception {
        account("dispatcher", Role.DISPATCHER);
        Shipment s = shipment(warehouse(), 30.81, 75.17, 40);
        mvc.perform(tracking("/track/complaint", s).param("category", "NOT_RECEIVED")
                .param("message", "Nothing arrived").param("phone", "98765 43210"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("SR-")));
        Complaint c = complaints.findAll().getFirst();
        assertThat(c.getCallbackPhone()).isEqualTo("9876543210");
        assertThat(jdbc.queryForObject("select count(*) from notifications", Integer.class)).isEqualTo(1);

        mvc.perform(tracking("/track/complaint", s).param("category", "LATE").param("message", "Again").param("phone", "9876543210"))
            .andExpect(content().string(containsString("already have an open complaint")));
        assertThat(complaints.count()).isEqualTo(1);
        // The tracking page shows the masked number, never the full one.
        mvc.perform(tracking("/track", s)).andExpect(content().string(containsString("98xxxxx210")))
            .andExpect(content().string(not(containsString("9876543210"))));
    }

    @Test
    void warehouseStaffSeeOnlyTheirOwnWarehouse() throws Exception {
        Warehouse ludhiana = warehouse();
        Warehouse patiala = warehouses.save(new Warehouse("PTA", "Patiala Depot", "Patiala", "Sirhind Road", 30.356, 76.378));
        UserAccount desk = account("hk", Role.WAREHOUSE);
        desk.setWarehouseId(patiala.getId());
        users.save(desk);

        Shipment ours = shipment(patiala, 30.4, 76.4, 20);
        Shipment theirs = shipment(ludhiana, 30.8, 75.2, 20);
        mvc.perform(tracking("/track/complaint", ours).param("category", "LATE").param("message", "Late").param("phone", "9876543210"));
        mvc.perform(tracking("/track/complaint", theirs).param("category", "LATE").param("message", "Late").param("phone", "9876543211"));
        long ourId = complaints.findAll().stream().filter(c -> c.getWarehouseId().equals(patiala.getId())).findFirst().orElseThrow().getId();
        long theirId = complaints.findAll().stream().filter(c -> c.getWarehouseId().equals(ludhiana.getId())).findFirst().orElseThrow().getId();

        mvc.perform(get("/desk").with(user("hk").roles("WAREHOUSE"))).andExpect(status().isOk())
            .andExpect(content().string(containsString(ours.getTrackingNo())))
            .andExpect(content().string(not(containsString(theirs.getTrackingNo()))));
        mvc.perform(get("/complaints/" + ourId).with(user("hk").roles("WAREHOUSE"))).andExpect(status().isOk());
        mvc.perform(get("/complaints/" + theirId).with(user("hk").roles("WAREHOUSE"))).andExpect(status().isNotFound());
        mvc.perform(post("/complaints/" + theirId + "/resolve").param("reply", "x").with(user("hk").roles("WAREHOUSE")).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(get("/tower").with(user("hk").roles("WAREHOUSE"))).andExpect(status().isForbidden());
        mvc.perform(get("/shipments").with(user("hk").roles("WAREHOUSE"))).andExpect(status().isForbidden());
        mvc.perform(get("/desk").with(user("x").roles("DISPATCHER"))).andExpect(status().isForbidden());
        // Dispatchers see every warehouse's complaints.
        mvc.perform(get("/complaints/" + theirId).with(user("x").roles("DISPATCHER"))).andExpect(status().isOk());
    }

    @Test
    void theDesksReplyIsShownToTheCustomer() throws Exception {
        Warehouse w = warehouse();
        UserAccount desk = account("hk", Role.WAREHOUSE);
        desk.setWarehouseId(w.getId());
        users.save(desk);
        Shipment s = shipment(w, 30.81, 75.17, 40);
        mvc.perform(tracking("/track/complaint", s).param("category", "DAMAGED").param("message", "Box was crushed").param("phone", "9876543210"));
        long id = complaints.findAll().getFirst().getId();

        mvc.perform(post("/complaints/" + id + "/take").with(user("hk").roles("WAREHOUSE")).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(tracking("/track", s)).andExpect(content().string(containsString("Being looked at")));
        mvc.perform(post("/complaints/" + id + "/resolve").param("reply", "A replacement leaves tomorrow morning.")
            .with(user("hk").roles("WAREHOUSE")).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(tracking("/track", s)).andExpect(content().string(containsString("A replacement leaves tomorrow morning.")));
        assertThat(complaints.findById(id).orElseThrow().getStatus()).isEqualTo(Complaint.Status.RESOLVED);
    }
}
