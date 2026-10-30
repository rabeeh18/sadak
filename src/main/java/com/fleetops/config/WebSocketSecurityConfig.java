package com.fleetops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

/**
 * Authorizes STOMP traffic, not just the HTTP handshake: only dispatchers and admins may
 * subscribe to fleet-wide topics; per-user queues are open to any signed-in user.
 * CSRF is enforced on CONNECT (the page sends its token in the CONNECT headers).
 */
@Configuration
@EnableWebSocketSecurity
public class WebSocketSecurityConfig {

    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager(MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        return messages
            .simpTypeMatchers(SimpMessageType.CONNECT, SimpMessageType.DISCONNECT, SimpMessageType.HEARTBEAT,
                SimpMessageType.UNSUBSCRIBE).authenticated()
            .simpSubscribeDestMatchers("/topic/fleet/**").hasAnyRole("ADMIN", "DISPATCHER")
            .simpSubscribeDestMatchers("/user/queue/**").authenticated()
            .simpDestMatchers("/app/**").denyAll()
            .anyMessage().denyAll()
            .build();
    }
}
