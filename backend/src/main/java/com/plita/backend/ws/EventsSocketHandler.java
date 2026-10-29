package com.plita.backend.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plita.backend.engine.Engine;
import com.plita.backend.security.AuthException;
import com.plita.backend.security.JwtService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket /api/v1/ws - события для приложения («красный экран»).
 *
 * <pre>
 * Подключение: wss://&lt;домен&gt;/api/v1/ws?token=&lt;JWT&gt; (или заголовок Authorization: Bearer)
 * Сразу после подключения: {"type": "snapshot", "homes": [...]}, дальше события:
 *   {"type": "alarm", "home_id", "reason", "details", "command_sent", "at"}  - ТРЕВОГА
 *   {"type": "state", "home_id", "state", "verdict", "process", ...}           - смена состояния
 *   {"type": "sensor_offline" | "relay_offline" | "sensor_online" | "relay_online", "home_id", ...}
 * Клиент может слать "ping" - ответ "pong".
 * </pre>
 */
@Component
public class EventsSocketHandler extends TextWebSocketHandler {

    private static final String USER_ID = "userId";
    private static final String DECORATED = "decorated";

    private final JwtService jwt;
    private final WsHub hub;
    private final Engine engine;
    private final ObjectMapper mapper;

    public EventsSocketHandler(JwtService jwt, WsHub hub, Engine engine, ObjectMapper mapper) {
        this.jwt = jwt;
        this.hub = hub;
        this.engine = engine;
        this.mapper = mapper;
    }

    public HandshakeInterceptor handshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
                String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                if ((token == null || token.isBlank()) && auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    token = auth.substring(7).trim();
                }
                try {
                    attributes.put(USER_ID, jwt.verify(token));
                    return true;
                } catch (AuthException e) {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                }
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception exception) {
                // не нужно
            }
        };
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        long userId = (Long) session.getAttributes().get(USER_ID);
        // отправка из разных потоков (MQTT, сторож, REST) - нужен потокобезопасный декоратор
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5000, 256 * 1024);
        session.getAttributes().put(DECORATED, safe);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", "snapshot");
        List<Map<String, Object>> homes = engine.homesOfUser(userId).stream().map(engine::status).toList();
        snapshot.put("homes", homes);
        safe.sendMessage(new TextMessage(mapper.writeValueAsString(snapshot)));
        hub.add(userId, safe);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if ("ping".equalsIgnoreCase(message.getPayload().trim())) {
            ((WebSocketSession) session.getAttributes().get(DECORATED)).sendMessage(new TextMessage("pong"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object uid = session.getAttributes().get(USER_ID);
        Object safe = session.getAttributes().get(DECORATED);
        if (uid instanceof Long id && safe instanceof WebSocketSession s) {
            hub.remove(id, s);
        }
    }
}
