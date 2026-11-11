package com.plita.backend.api;

import com.plita.backend.ws.EventsSocketHandler;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.List;

@Configuration
@EnableWebSocket
public class WebConfig implements WebMvcConfigurer, WebSocketConfigurer {

    static {
        // CurrentUser берётся из заголовка Authorization, а не из параметров запроса - не показывать в /docs
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentUser.class);
    }

    private final CurrentUser.Resolver currentUserResolver;
    private final EventsSocketHandler eventsHandler;

    public WebConfig(CurrentUser.Resolver currentUserResolver, EventsSocketHandler eventsHandler) {
        this.currentUserResolver = currentUserResolver;
        this.eventsHandler = eventsHandler;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserResolver);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // мобильные приложения не шлют Origin браузера - разрешаем любой, доступ проверяется по JWT
        registry.addHandler(eventsHandler, "/api/v1/ws")
                .addInterceptors(eventsHandler.handshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }
}
