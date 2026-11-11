package com.plita.backend.api;

import com.plita.backend.PlitaApplication;
import com.plita.backend.engine.Engine;
import com.plita.backend.push.PushService;
import com.plita.backend.relay.TuyaService;
import com.plita.backend.ws.WsHub;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** GET /health - всегда 200, пока процесс жив (по нему автодеплой проверяет выкатку). */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final Engine engine;
    private final WsHub hub;
    private final TuyaService tuya;
    private final PushService push;

    public HealthController(JdbcTemplate jdbc, Engine engine, WsHub hub, TuyaService tuya, PushService push) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.hub = hub;
        this.tuya = tuya;
        this.push = push;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean db;
        try {
            db = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException e) {
            db = false;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("version", PlitaApplication.VERSION);
        m.put("db", db);
        m.put("mqtt", engine.mqttConnected());
        m.put("tuya", tuya.enabled());
        m.put("push", push.enabled());
        m.put("ws_clients", hub.connections());
        m.put("stats", engine.stats());
        return m;
    }
}
