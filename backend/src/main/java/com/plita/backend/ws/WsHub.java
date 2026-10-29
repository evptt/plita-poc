package com.plita.backend.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Доставка событий в приложение по WebSocket («красный экран»).
 *
 * <p>В ТЗ канал не выбран (вопрос 5). WebSocket выбран, потому что Caddy его уже пропускает
 * и не нужен внешний сервис. Push через Firebase можно добавить позже: события формируются
 * в одном месте (Engine).
 */
@Component
public class WsHub {

    private static final Logger log = LoggerFactory.getLogger(WsHub.class);

    private final Map<Long, Set<WebSocketSession>> byUser = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public WsHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void add(long userId, WebSocketSession s) {
        byUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(s);
    }

    public void remove(long userId, WebSocketSession s) {
        byUser.computeIfPresent(userId, (k, set) -> {
            set.remove(s);
            return set.isEmpty() ? null : set;
        });
    }

    public int connections() {
        return byUser.values().stream().mapToInt(Set::size).sum();
    }

    public void sendToUser(long userId, Map<String, Object> event) {
        Set<WebSocketSession> sessions = byUser.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        TextMessage msg;
        try {
            msg = new TextMessage(mapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.error("Не удалось сериализовать событие {}", event.get("type"), e);
            return;
        }
        for (WebSocketSession s : sessions) {
            try {
                if (s.isOpen()) {
                    s.sendMessage(msg);
                } else {
                    remove(userId, s);
                }
            } catch (IOException | RuntimeException e) {
                remove(userId, s);
            }
        }
    }
}
