package com.plita.backend.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plita.backend.analysis.AnalysisParams;
import com.plita.backend.analysis.Decision;
import com.plita.backend.analysis.HomeAnalyzer;
import com.plita.backend.analysis.Model.HomeState;
import com.plita.backend.analysis.Model.RelaySample;
import com.plita.backend.analysis.Model.SensorSample;
import com.plita.backend.analysis.Model.Verdict;
import com.plita.backend.config.AppSettings;
import com.plita.backend.ingest.MqttService;
import com.plita.backend.ingest.Packets;
import com.plita.backend.ingest.Packets.PacketException;
import com.plita.backend.ingest.Packets.RelayReading;
import com.plita.backend.ingest.Packets.SensorReading;
import com.plita.backend.ingest.Packets.Topic;
import com.plita.backend.ingest.RelayPacketParser;
import com.plita.backend.ws.WsHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Связка модулей бэкенда (рис. 1 разбора ТЗ):
 * <ul>
 *   <li>MQTT Ingest - разбор пакетов, запись в telemetry_history, передача в анализ;</li>
 *   <li>Analysis Engine - {@link HomeAnalyzer} на каждый Дом;</li>
 *   <li>Command Service - публикация 0/1 в relay/{id}/command по тревоге или по команде пользователя;</li>
 *   <li>уведомления - события в приложение через WebSocket.</li>
 * </ul>
 * Все изменения состояния идут под одной блокировкой (synchronized): MQTT-поток, сторож и
 * REST-запросы не мешают друг другу. Нагрузка PoC - единицы сообщений в секунду.
 */
@Service
public class Engine {

    private static final Logger log = LoggerFactory.getLogger(Engine.class);
    private static final long UNKNOWN_DEVICE_RECHECK_MS = 30_000;

    public record DeviceInfo(String type, Long homeId) {
    }

    public static final class HomeInfo {
        public final long userId;
        public final String name;
        public String sensorId;
        public String relayId;

        HomeInfo(long userId, String name) {
            this.userId = userId;
            this.name = name;
        }
    }

    private final JdbcTemplate jdbc;
    private final AppSettings settings;
    private final AnalysisParams params;
    private final WsHub hub;
    private final MqttService mqtt;
    private final ObjectMapper mapper;
    private final RelayPacketParser relayParser;

    private final Map<String, DeviceInfo> devices = new HashMap<>();
    private final Map<Long, HomeInfo> homes = new HashMap<>();
    private final Map<Long, HomeAnalyzer> analyzers = new HashMap<>();
    private final Map<String, Long> unknownUntil = new HashMap<>();
    private final Map<String, AtomicLong> stats = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public Engine(JdbcTemplate jdbc, AppSettings settings, AnalysisParams params, WsHub hub,
                  MqttService mqtt, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.params = params;
        this.hub = hub;
        this.mqtt = mqtt;
        this.mapper = mapper;
        this.relayParser = new RelayPacketParser(mapper);
        for (String k : List.of("sensor_packets", "relay_packets", "bad_packets", "unknown_device_packets",
                "alarms", "commands")) {
            stats.put(k, new AtomicLong());
        }
    }

    // ------------------------------------------------------------------ загрузка

    public synchronized void load() {
        homes.clear();
        devices.clear();
        analyzers.clear();
        jdbc.query("SELECT id, user_id, name FROM homes", rs -> {
            homes.put(rs.getLong("id"), new HomeInfo(rs.getLong("user_id"), rs.getString("name")));
        });
        jdbc.query("SELECT id, type::text AS type, home_id FROM devices", rs -> {
            long hid = rs.getLong("home_id");
            devices.put(rs.getString("id"), new DeviceInfo(rs.getString("type"), rs.wasNull() ? null : hid));
        });
        devices.forEach(this::attachDevice);

        Map<Long, Map<String, Object>> states = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM home_state")) {
            states.put(((Number) row.get("home_id")).longValue(), row);
        }
        for (Long homeId : homes.keySet()) {
            createAnalyzer(homeId, states.get(homeId));
        }
        loaded = true;
        log.info("Загружено: Домов {}, устройств {}", homes.size(), devices.size());
    }

    private void attachDevice(String devId, DeviceInfo d) {
        HomeInfo home = d.homeId() == null ? null : homes.get(d.homeId());
        if (home == null) {
            return;
        }
        if ("sensor".equals(d.type())) {
            home.sensorId = devId;
        } else if ("relay".equals(d.type())) {
            home.relayId = devId;
        }
    }

    private HomeAnalyzer createAnalyzer(long homeId, Map<String, Object> row) {
        boolean protection = row == null || Boolean.TRUE.equals(row.get("protection_enabled"));
        HomeAnalyzer a = new HomeAnalyzer(homeId, params, protection);
        if (row != null) {
            String st = (String) row.get("state");
            if (HomeState.ALARM.value().equals(st) || HomeState.RELAY_OFF.value().equals(st)) {
                // тревога переживает перезапуск сервиса: снять её можно только вручную из приложения
                String v = (String) row.get("verdict");
                a.restore(HomeState.fromValue(st), v == null ? Verdict.ALARM : Verdict.valueOf(v),
                        (String) row.get("process"), (String) row.get("reason"));
            }
        }
        analyzers.put(homeId, a);
        return a;
    }

    /** Вызывается после создания/удаления Дома. */
    public synchronized void reloadHome(long homeId) {
        devices.replaceAll((id, d) -> homeIdEquals(d, homeId) ? new DeviceInfo(d.type(), null) : d);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, user_id, name FROM homes WHERE id = ?", homeId);
        if (rows.isEmpty()) {
            homes.remove(homeId);
            analyzers.remove(homeId);
            return;
        }
        Map<String, Object> row = rows.get(0);
        homes.put(homeId, new HomeInfo(((Number) row.get("user_id")).longValue(), (String) row.get("name")));
        jdbc.query("SELECT id, type::text AS type, home_id FROM devices WHERE home_id = ?", rs -> {
            String id = rs.getString("id");
            DeviceInfo d = new DeviceInfo(rs.getString("type"), rs.getLong("home_id"));
            devices.put(id, d);
            unknownUntil.remove(id);
            attachDevice(id, d);
        }, homeId);
        if (!analyzers.containsKey(homeId)) {
            List<Map<String, Object>> st = jdbc.queryForList("SELECT * FROM home_state WHERE home_id = ?", homeId);
            createAnalyzer(homeId, st.isEmpty() ? null : st.get(0));
        }
    }

    private static boolean homeIdEquals(DeviceInfo d, long homeId) {
        return d.homeId() != null && d.homeId() == homeId;
    }

    private DeviceInfo lookupDevice(String devId) {
        DeviceInfo d = devices.get(devId);
        if (d != null) {
            return d;
        }
        long now = System.currentTimeMillis();
        Long until = unknownUntil.get(devId);
        if (until != null && until > now) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, type::text AS type, home_id FROM devices WHERE id = ?", devId);
        if (rows.isEmpty()) {
            unknownUntil.put(devId, now + UNKNOWN_DEVICE_RECHECK_MS);
            log.warn("Пакет от незаведённого устройства {} пропущен (заведите его в devices "
                    + "или создайте Дом через POST /api/v1/homes)", devId);
            return null;
        }
        Object hid = rows.get(0).get("home_id");
        d = new DeviceInfo((String) rows.get(0).get("type"), hid == null ? null : ((Number) hid).longValue());
        devices.put(devId, d);
        attachDevice(devId, d);
        return d;
    }

    // ------------------------------------------------------------------ MQTT Ingest

    public synchronized void handleMessage(String topicName, byte[] payload) {
        Topic topic = Packets.parseTopic(topicName);
        if (topic == null) {
            return;
        }
        DeviceInfo device = lookupDevice(topic.deviceId());
        if (device == null) {
            inc("unknown_device_packets");
            return;
        }
        String kind = topic.kind() == Packets.Kind.SENSOR ? "sensor" : "relay";
        if (!kind.equals(device.type())) {
            inc("bad_packets");
            log.warn("Устройство {} заведено как {}, но пишет в топик {}", topic.deviceId(), device.type(), topicName);
            return;
        }

        double t = System.currentTimeMillis() / 1000.0;
        HomeAnalyzer analyzer = device.homeId() == null ? null : analyzers.get(device.homeId());
        try {
            if (topic.kind() == Packets.Kind.SENSOR) {
                SensorReading r = Packets.parseSensorPacket(payload, settings.sensorByteOrder());
                inc("sensor_packets");
                // Таблица из разбора ТЗ: val_1 = Temp, val_2 = Hum, val_3 = VOC, val_4 = Radar
                jdbc.update("INSERT INTO telemetry_history (device_id, \"timestamp\", val_1, val_2, val_3, val_4) "
                        + "VALUES (?, now(), ?, ?, ?, ?)", topic.deviceId(), r.temperature(), r.humidity(), r.voc(),
                        (double) r.radar());
                if (analyzer != null) {
                    apply(device.homeId(), analyzer.onSensor(
                            new SensorSample(t, r.humidity(), r.temperature(), r.voc(), r.radar())));
                }
            } else {
                RelayReading r = relayParser.parse(payload);
                inc("relay_packets");
                // val_1 = напряжение, val_2 = ток, val_3 = NULL, val_4 = статус реле
                jdbc.update("INSERT INTO telemetry_history (device_id, \"timestamp\", val_1, val_2, val_3, val_4) "
                        + "VALUES (?, now(), ?, ?, NULL, ?)", topic.deviceId(), r.voltage(), r.current(),
                        (double) r.state());
                if (analyzer != null) {
                    apply(device.homeId(), analyzer.onRelay(new RelaySample(t, r.voltage(), r.current(), r.state())));
                }
            }
        } catch (PacketException e) {
            inc("bad_packets");
            log.warn("Битый пакет в {}: {}", topicName, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ решения анализатора

    private void apply(long homeId, List<Decision> decisions) {
        for (Decision d : decisions) {
            switch (d) {
                case Decision.StateChanged sc -> {
                    saveState(homeId);
                    log.info("Дом {}: состояние {}, вердикт {}, процесс {}", homeId, sc.state().value(),
                            sc.verdict(), sc.process());
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("type", "state");
                    ev.putAll(status(homeId));
                    notifyHome(homeId, ev);
                }
                case Decision.Alarm al -> handleAlarm(homeId, al);
                case Decision.Notice n -> {
                    log.warn("Дом {}: {} {}", homeId, n.kind(), n.details());
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("type", n.kind());
                    ev.put("home_id", homeId);
                    ev.put("details", n.details());
                    ev.put("at", Instant.now().toString());
                    notifyHome(homeId, ev);
                }
            }
        }
    }

    private void handleAlarm(long homeId, Decision.Alarm alarm) {
        inc("alarms");
        log.error("Дом {}: ТРЕВОГА ({}) {}", homeId, alarm.reason().value(), alarm.details());
        boolean commandSent = false;
        if (alarm.cutoff()) {
            commandSent = sendCommand(homeId, 0, "auto", null, alarm.reason().value());
        }
        jdbc.update("INSERT INTO alarms (home_id, reason, details, command_sent) VALUES (?, ?, ?::jsonb, ?)",
                homeId, alarm.reason().value(), toJson(alarm.details()), commandSent);
        // «красный экран»
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "alarm");
        ev.put("home_id", homeId);
        ev.put("reason", alarm.reason().value());
        ev.put("details", alarm.details());
        ev.put("command_sent", commandSent);
        ev.put("at", Instant.now().toString());
        notifyHome(homeId, ev);
        if (commandSent) {
            apply(homeId, analyzers.get(homeId).markRelayOff());
        } else {
            saveState(homeId);
        }
    }

    private void saveState(long homeId) {
        HomeAnalyzer a = analyzers.get(homeId);
        jdbc.update("""
                INSERT INTO home_state (home_id, state, verdict, process, reason, protection_enabled, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (home_id) DO UPDATE SET state = EXCLUDED.state, verdict = EXCLUDED.verdict,
                    process = EXCLUDED.process, reason = EXCLUDED.reason, updated_at = now()""",
                homeId, a.state().value(), a.verdict().name(), a.process(), a.lastReason(), a.protectionEnabled());
    }

    private void notifyHome(long homeId, Map<String, Object> event) {
        HomeInfo home = homes.get(homeId);
        if (home != null) {
            hub.sendToUser(home.userId, event);
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // ------------------------------------------------------------------ Command Service

    private boolean sendCommand(long homeId, int command, String source, Long userId, String reason) {
        HomeInfo home = homes.get(homeId);
        if (home == null || home.relayId == null) {
            log.error("Дом {}: нет реле, команду {} отправить некуда", homeId, command);
            return false;
        }
        try {
            mqtt.sendRelayCommand(home.relayId, command);
        } catch (RuntimeException e) {
            log.error("Дом {}: команда {} в relay/{}/command не отправлена: {}", homeId, command, home.relayId,
                    e.getMessage());
            return false;
        }
        inc("commands");
        log.info("Дом {}: команда {} -> relay/{}/command ({})", homeId, command, home.relayId, source);
        jdbc.update("INSERT INTO relay_commands (relay_id, home_id, command, source, user_id, reason) "
                + "VALUES (?, ?, ?, ?, ?, ?)", home.relayId, homeId, command, source, userId, reason);
        return true;
    }

    /** Ручная команда из приложения; 1 после тревоги = ручной сброс (RelayOff/Alarm -> Idle). */
    public synchronized boolean manualCommand(long homeId, int command, long userId) {
        boolean ok = sendCommand(homeId, command, "manual", userId, null);
        if (ok && command == 1) {
            apply(homeId, analyzers.get(homeId).manualReset());
            saveState(homeId);
        }
        return ok;
    }

    public synchronized void setProtection(long homeId, boolean enabled) {
        HomeAnalyzer a = analyzers.get(homeId);
        a.setProtectionEnabled(enabled);
        jdbc.update("""
                INSERT INTO home_state (home_id, state, verdict, protection_enabled) VALUES (?, ?, ?, ?)
                ON CONFLICT (home_id) DO UPDATE SET protection_enabled = EXCLUDED.protection_enabled,
                    updated_at = now()""", homeId, a.state().value(), a.verdict().name(), enabled);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "state");
        ev.putAll(status(homeId));
        notifyHome(homeId, ev);
    }

    // ------------------------------------------------------------------ сторож связи

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public synchronized void watchdog() {
        if (!loaded) {
            return;
        }
        double now = System.currentTimeMillis() / 1000.0;
        for (var e : new ArrayList<>(analyzers.entrySet())) {
            try {
                apply(e.getKey(), e.getValue().onTick(now));
            } catch (RuntimeException ex) {
                log.error("Сторож: ошибка для Дома {}", e.getKey(), ex);
            }
        }
    }

    // ------------------------------------------------------------------ для API

    public synchronized HomeInfo home(long homeId) {
        return homes.get(homeId);
    }

    public synchronized List<Long> homesOfUser(long userId) {
        return homes.entrySet().stream().filter(e -> e.getValue().userId == userId).map(Map.Entry::getKey)
                .sorted().toList();
    }

    public synchronized Map<String, Object> status(long homeId) {
        HomeAnalyzer a = analyzers.get(homeId);
        HomeInfo home = homes.get(homeId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("home_id", homeId);
        m.put("sensor_id", home == null ? null : home.sensorId);
        m.put("relay_id", home == null ? null : home.relayId);
        if (a != null) {
            m.put("state", a.state().value());
            m.put("verdict", a.verdict().name());
            m.put("process", a.process());
            m.put("reason", a.lastReason());
            m.put("stove_on", a.stoveOn());
            m.put("protection_enabled", a.protectionEnabled());
            m.put("coefficients", a.coefficients());
            m.put("sensor_offline", a.sensorOffline());
            m.put("relay_offline", a.relayOffline());
            SensorSample s = a.lastSensor();
            if (s == null) {
                m.put("last_sensor", null);
            } else {
                Map<String, Object> ls = new LinkedHashMap<>();
                ls.put("at", iso(s.t()));
                ls.put("temperature", s.temperature());
                ls.put("humidity", s.humidity());
                ls.put("voc", s.voc());
                ls.put("radar", s.radar());
                m.put("last_sensor", ls);
            }
            RelaySample r = a.lastRelay();
            if (r == null) {
                m.put("last_relay", null);
            } else {
                Map<String, Object> lr = new LinkedHashMap<>();
                lr.put("at", iso(r.t()));
                lr.put("voltage", r.voltage());
                lr.put("current", r.current());
                lr.put("state", r.state());
                m.put("last_relay", lr);
            }
        }
        return m;
    }

    private static String iso(double t) {
        return Instant.ofEpochMilli(Math.round(t * 1000)).toString();
    }

    private void inc(String key) {
        stats.get(key).incrementAndGet();
    }

    public Map<String, Long> stats() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String k : List.of("sensor_packets", "relay_packets", "bad_packets", "unknown_device_packets",
                "alarms", "commands")) {
            m.put(k, stats.get(k).get());
        }
        return m;
    }

    public boolean mqttConnected() {
        return mqtt.isConnected();
    }
}
