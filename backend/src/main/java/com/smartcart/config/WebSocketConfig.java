package com.smartcart.config;

import com.smartcart.auth.AuthUser;
import com.smartcart.auth.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;
import java.util.Set;

/**
 * STOMP over WebSocket at /ws-smartcart. Admin dashboards subscribe to /topic/jobs for live
 * background-job status. The simple in-memory broker suits a single instance; a cluster needs a
 * relay to RabbitMQ/ActiveMQ.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    public static final String JOBS_TOPIC = "/topic/jobs";

    /** Clients only listen; they never publish to the broker themselves. */
    private static final Set<StompCommand> CLIENT_COMMANDS =
            Set.of(StompCommand.CONNECT, StompCommand.STOMP, StompCommand.SUBSCRIBE,
                    StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT);

    private final JwtService jwt;
    private final List<String> allowedOrigins;

    public WebSocketConfig(JwtService jwt, AppProperties props) {
        this.jwt = jwt;
        this.allowedOrigins = props.cors().allowedOrigins();
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-smartcart").setAllowedOriginPatterns(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    /**
     * The HTTP upgrade is unauthenticated (browsers cannot set headers on it), so the admin JWT is
     * checked on the STOMP CONNECT frame instead: "Authorization: Bearer <access token>".
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (stomp == null || stomp.getCommand() == null) {
                    return message; // heartbeat
                }
                StompCommand command = stomp.getCommand();
                if (!CLIENT_COMMANDS.contains(command)) {
                    throw new MessageDeliveryException("Command not allowed: " + command);
                }
                if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
                    AuthUser user = adminFrom(stomp.getFirstNativeHeader("Authorization"));
                    stomp.setUser(new UsernamePasswordAuthenticationToken(
                            user, null, List.of(new SimpleGrantedAuthority(user.role()))));
                } else if (stomp.getUser() == null) {
                    throw new MessageDeliveryException("Not connected");
                }
                if (command == StompCommand.SUBSCRIBE && !JOBS_TOPIC.equals(stomp.getDestination())) {
                    throw new MessageDeliveryException("Unknown destination");
                }
                return message;
            }
        });
    }

    private AuthUser adminFrom(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessageDeliveryException("Authentication required");
        }
        AuthUser user = jwt.verify(header.substring(7).trim())
                .orElseThrow(() -> new MessageDeliveryException("Invalid or expired token"));
        if (!user.isAdmin()) {
            throw new MessageDeliveryException("Access denied");
        }
        return user;
    }
}
