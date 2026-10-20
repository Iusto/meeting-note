package dev.aimeeting.api.live;

import dev.aimeeting.api.config.AppProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final LiveWebSocketHandler handler;
    private final LiveHandshakeInterceptor interceptor;
    private final AppProperties properties;

    public WebSocketConfig(LiveWebSocketHandler handler, LiveHandshakeInterceptor interceptor, AppProperties properties) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/meetings/{id}/live")
                .addInterceptors(interceptor)
                .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }
}
