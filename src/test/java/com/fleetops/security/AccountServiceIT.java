package com.fleetops.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import com.fleetops.IntegrationTestSupport;
import com.fleetops.common.web.BusinessRuleException;
import com.fleetops.fleet.Driver;
import com.fleetops.fleet.Warehouse;

@AutoConfigureMockMvc
class AccountServiceIT extends IntegrationTestSupport {

    @Autowired AccountService accounts;
    @Autowired UserTokenRepository tokens;
    @Autowired MockMvc mvc;

    private static String token(AccountService.Link link) {
        return link.url().substring(link.url().indexOf("token=") + 6);
    }

    private void actAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(username, null, java.util.List.of()));
    }

    @Test
    void anInvitedPersonSignsInWithEmailOnlyAfterChoosingAPassword() throws Exception {
        AccountService.Link link = accounts.invite("Harleen Gill", "Harleen.Gill@Example.com", Role.DISPATCHER, null);
        assertThat(link.email()).isEqualTo("harleen.gill@example.com");

        mvc.perform(formLogin().userParameter("email").user("harleen.gill@example.com").password("anything-at-all"))
            .andExpect(unauthenticated());

        accounts.choosePassword(token(link), "a-good-password", "a-good-password");

        mvc.perform(formLogin().userParameter("email").user("HARLEEN.GILL@example.com").password("a-good-password"))
            .andExpect(authenticated().withRoles("DISPATCHER"));
        mvc.perform(formLogin().userParameter("email").user("harleen.gill@example.com").password("wrong-password"))
            .andExpect(unauthenticated());
    }

    @Test
    void aLinkWorksOnceAndANewerLinkRetiresOlderOnes() {
        UserAccount u = users.findByEmail(accounts.invite("Aman Sidhu", "aman@example.com", Role.DISPATCHER, null).email()).orElseThrow();
        AccountService.Link first = accounts.resendInvite(u.getId());
        AccountService.Link second = accounts.resendInvite(u.getId());

        assertThat(accounts.usableToken(token(first))).isEmpty();
        accounts.choosePassword(token(second), "a-good-password", "a-good-password");
        assertThatThrownBy(() -> accounts.choosePassword(token(second), "another-password", "another-password"))
            .isInstanceOf(BusinessRuleException.class);
        assertThat(tokens.findAll()).allMatch(t -> !t.usableAt(Instant.now()));
    }

    @Test
    void driverAccountsMustBeLinkedToAFreeDriverRecord() {
        Warehouse w = warehouse();
        Driver d = driver(w, "Gurpreet Singh");
        assertThatThrownBy(() -> accounts.invite("Gurpreet Singh", "g@example.com", Role.DRIVER, null))
            .isInstanceOf(BusinessRuleException.class);

        accounts.invite("Gurpreet Singh", "g@example.com", Role.DRIVER, d.getId());

        assertThat(jdbc.queryForObject("select u.email from drivers d join users u on u.id = d.user_id where d.id = ?",
            String.class, d.getId())).isEqualTo("g@example.com");
        assertThatThrownBy(() -> accounts.invite("Someone Else", "else@example.com", Role.DRIVER, d.getId()))
            .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> accounts.invite("Duplicate", "G@example.com", Role.DISPATCHER, null))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void theLastAdminCannotBeDisabledOrDemoted() {
        UserAccount boss = account("boss", Role.ADMIN);
        account("someone", Role.ADMIN);
        actAs("someone");
        accounts.toggleEnabled(boss.getId()); // two admins: allowed
        accounts.toggleEnabled(boss.getId()); // enabled again

        UserAccount someone = users.findByUsername("someone").orElseThrow();
        actAs("boss");
        accounts.changeRole(someone.getId(), Role.DISPATCHER); // boss stays the only admin

        actAs("someone");
        assertThatThrownBy(() -> accounts.changeRole(boss.getId(), Role.DISPATCHER)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> accounts.toggleEnabled(boss.getId())).isInstanceOf(BusinessRuleException.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    void warehouseAccountsBelongToExactlyOneWarehouse() {
        var w = warehouse();
        assertThatThrownBy(() -> accounts.invite("Harleen Kaur", "hk@example.com", Role.WAREHOUSE, null, null))
            .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> accounts.invite("Simran Kaur", "sk@example.com", Role.DISPATCHER, null, w.getId()))
            .isInstanceOf(BusinessRuleException.class);
        accounts.invite("Harleen Kaur", "hk@example.com", Role.WAREHOUSE, null, w.getId());
        assertThat(users.findByEmail("hk@example.com").orElseThrow().getWarehouseId()).isEqualTo(w.getId());
    }
}
