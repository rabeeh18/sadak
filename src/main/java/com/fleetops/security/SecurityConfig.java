package com.fleetops.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/demo-login", "/demo-reset", "/track", "/track/**", "/error",
                        "/css/**", "/js/**", "/img/**", "/fonts/**", "/favicon.svg", "/actuator/health").permitAll()
                .requestMatchers("/driver/**", "/api/driver/**").hasRole("DRIVER")
                .requestMatchers("/desk", "/desk/**").hasRole("WAREHOUSE")
                .requestMatchers("/complaints", "/complaints/**").hasAnyRole("DISPATCHER", "WAREHOUSE")
                .requestMatchers("/api/telemetry/**").hasRole("DRIVER")
                .requestMatchers("/ws/**").hasRole("DISPATCHER")
                .requestMatchers("/").authenticated()
                .anyRequest().hasRole("DISPATCHER"))
            // Sign-in is the demo role picker on /login; anyone not signed in is sent there.
            .exceptionHandling(e -> e.authenticationEntryPoint(
                new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login")))
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?signedOut"))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(String.join("; ",
                    "default-src 'self'",
                    "script-src 'self' https://cdn.jsdelivr.net",
                    "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com",
                    "font-src 'self' https://fonts.gstatic.com",
                    "img-src 'self' data: https://tile.openstreetmap.org",
                    "connect-src 'self'",
                    "frame-ancestors 'self'"))));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Looks accounts up by email. Sign-in itself is the demo picker; this also stops Spring creating a default user. */
    @Bean
    UserDetailsService userDetailsService(UserAccountRepository users) {
        return email -> users.findByEmail(UserAccount.normaliseEmail(email))
            .map(SecurityConfig::toUserDetails)
            .orElseThrow(() -> new UsernameNotFoundException(email));
    }

    /** The signed-in principal: the internal username (used by audit) and the account's roles. */
    public static UserDetails toUserDetails(UserAccount u) {
        return User.withUsername(u.getUsername())
            .password(u.getPasswordHash())
            .disabled(!u.isEnabled())
            .authorities(u.getRoles().stream().map(Role::authority).toArray(String[]::new))
            .build();
    }
}
