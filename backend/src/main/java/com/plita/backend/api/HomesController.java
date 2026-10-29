package com.plita.backend.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plita.backend.engine.Engine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Дома и сопряжение (рис. 3), ручное управление реле, состояние, тревоги. */
@RestController
@RequestMapping("/api/v1/homes")
public class HomesController {

    /** Без '/', '+', '#': они ломают MQTT-топики. */
    static final String DEVICE_ID = "^[A-Za-z0-9_.:\\-]{1,64}$";

    public record HomeCreate(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = DEVICE_ID) String sensorId,
            @NotNull @Pattern(regexp = DEVICE_ID) String relayId,
            @Size(max = 32) String sensorMac,
            @Size(max = 32) String relayMac,
            @Size(max = 32) String sensorFirmware,
            @Size(max = 32) String relayFirmware) {

        @AssertTrue(message = "sensor_id и relay_id должны отличаться")
        public boolean isDifferentIds() {
            return sensorId == null || !sensorId.equals(relayId);
        }
    }

    public record DeviceOut(String id, String type, String macAddress, String firmwareVersion) {
    }

    public record HomeOut(long homeId, String name, String sensorId, String relayId, List<DeviceOut> devices,
                          String state, String verdict, boolean protectionEnabled) {
    }

    /** 0 - выключить плиту, 1 - включить (и сбросить тревогу). */
    public record RelayCommand(@NotNull @Min(0) @Max(1) Integer command) {
    }

    public record ProtectionIn(@NotNull Boolean enabled) {
    }

    private record Home(long id, long userId, String name) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Engine engine;
    private final ObjectMapper mapper;

    public HomesController(JdbcTemplate jdbc, TransactionTemplate tx, Engine engine, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.engine = engine;
        this.mapper = mapper;
    }

    private Home ownHome(long homeId, CurrentUser user) {
        List<Home> rows = jdbc.query("SELECT id, user_id, name FROM homes WHERE id = ?",
                (rs, i) -> new Home(rs.getLong("id"), rs.getLong("user_id"), rs.getString("name")), homeId);
        if (rows.isEmpty() || rows.get(0).userId() != user.id()) {
            throw ApiErrors.error(HttpStatus.NOT_FOUND, "Дом не найден");
        }
        return rows.get(0);
    }

    private HomeOut homeOut(long homeId, String name) {
        List<DeviceOut> devs = jdbc.query(
                "SELECT id, type::text AS type, mac_address, firmware_version FROM devices WHERE home_id = ? ORDER BY type::text",
                (rs, i) -> new DeviceOut(rs.getString("id"), rs.getString("type"), rs.getString("mac_address"),
                        rs.getString("firmware_version")), homeId);
        Map<String, Object> st = engine.status(homeId);
        return new HomeOut(homeId, name,
                devs.stream().filter(d -> d.type().equals("sensor")).map(DeviceOut::id).findFirst().orElse(null),
                devs.stream().filter(d -> d.type().equals("relay")).map(DeviceOut::id).findFirst().orElse(null),
                devs,
                (String) st.getOrDefault("state", "Idle"),
                (String) st.getOrDefault("verdict", "NONE"),
                (Boolean) st.getOrDefault("protection_enabled", true));
    }

    @PostMapping
    public ResponseEntity<HomeOut> create(@Valid @RequestBody HomeCreate body, CurrentUser user) {
        Long homeId = tx.execute(status -> {
            for (String[] dev : new String[][]{{body.sensorId(), "sensor"}, {body.relayId(), "relay"}}) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT d.type::text AS type, d.home_id, h.user_id FROM devices d "
                                + "LEFT JOIN homes h ON h.id = d.home_id WHERE d.id = ? FOR UPDATE OF d", dev[0]);
                if (rows.isEmpty()) {
                    continue;
                }
                Map<String, Object> r = rows.get(0);
                if (!dev[1].equals(r.get("type"))) {
                    throw ApiErrors.error(HttpStatus.CONFLICT,
                            "устройство " + dev[0] + " заведено как " + r.get("type") + ", а не " + dev[1]);
                }
                if (r.get("home_id") != null) {
                    boolean mine = Objects.equals(((Number) r.get("user_id")).longValue(), user.id());
                    throw ApiErrors.error(HttpStatus.CONFLICT, "устройство " + dev[0] + " уже привязано к "
                            + (mine ? "другому вашему Дому" : "другому пользователю"));
                }
            }
            Long id = jdbc.queryForObject("INSERT INTO homes (user_id, name) VALUES (?, ?) RETURNING id",
                    Long.class, user.id(), body.name());
            String upsert = """
                    INSERT INTO devices (id, home_id, type, mac_address, firmware_version) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET home_id = EXCLUDED.home_id,
                        mac_address = COALESCE(EXCLUDED.mac_address, devices.mac_address),
                        firmware_version = COALESCE(EXCLUDED.firmware_version, devices.firmware_version)""";
            jdbc.update(upsert, body.sensorId(), id, "sensor", body.sensorMac(), body.sensorFirmware());
            jdbc.update(upsert, body.relayId(), id, "relay", body.relayMac(), body.relayFirmware());
            jdbc.update("INSERT INTO home_state (home_id) VALUES (?) ON CONFLICT DO NOTHING", id);
            return id;
        });
        engine.reloadHome(homeId);
        return ResponseEntity.status(HttpStatus.CREATED).body(homeOut(homeId, body.name()));
    }

    @GetMapping
    public List<HomeOut> list(CurrentUser user) {
        List<Home> rows = jdbc.query("SELECT id, user_id, name FROM homes WHERE user_id = ? ORDER BY id",
                (rs, i) -> new Home(rs.getLong("id"), rs.getLong("user_id"), rs.getString("name")), user.id());
        return rows.stream().map(h -> homeOut(h.id(), h.name())).toList();
    }

    @GetMapping("/{homeId}")
    public HomeOut get(@PathVariable long homeId, CurrentUser user) {
        Home h = ownHome(homeId, user);
        return homeOut(h.id(), h.name());
    }

    /** Удаляет Дом. Устройства и их история остаются, но отвязываются (их можно привязать заново). */
    @DeleteMapping("/{homeId}")
    public ResponseEntity<Void> delete(@PathVariable long homeId, CurrentUser user) {
        Home h = ownHome(homeId, user);
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE devices SET home_id = NULL WHERE home_id = ?", h.id());
            jdbc.update("DELETE FROM homes WHERE id = ?", h.id());
        });
        engine.reloadHome(h.id());
        return ResponseEntity.noContent().build();
    }

    /** Состояние Дома: Idle/Cooking/Classified/Normal/Alarm/RelayOff, вердикт, процесс, последние показания. */
    @GetMapping("/{homeId}/status")
    public Map<String, Object> status(@PathVariable long homeId, CurrentUser user) {
        return engine.status(ownHome(homeId, user).id());
    }

    @PostMapping("/{homeId}/relay/command")
    public Map<String, Object> relayCommand(@PathVariable long homeId, @Valid @RequestBody RelayCommand body,
                                            CurrentUser user) {
        Home h = ownHome(homeId, user);
        Engine.HomeInfo info = engine.home(h.id());
        if (info == null || info.relayId == null) {
            throw ApiErrors.error(HttpStatus.CONFLICT, "к Дому не привязано реле");
        }
        if (!engine.manualCommand(h.id(), body.command(), user.id())) {
            throw ApiErrors.error(HttpStatus.SERVICE_UNAVAILABLE, "MQTT-брокер недоступен, команда не отправлена");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("home_id", h.id());
        out.put("relay_id", info.relayId);
        out.put("command", body.command());
        out.put("sent", true);
        out.put("status", engine.status(h.id()));
        return out;
    }

    /**
     * Деактивация/активация защиты. При выключенной защите сервер продолжает анализ и шлёт тревоги
     * в приложение, но сам не отправляет реле команду 0.
     */
    @PutMapping("/{homeId}/protection")
    public Map<String, Object> protection(@PathVariable long homeId, @Valid @RequestBody ProtectionIn body,
                                          CurrentUser user) {
        Home h = ownHome(homeId, user);
        engine.setProtection(h.id(), body.enabled());
        return engine.status(h.id());
    }

    @GetMapping("/{homeId}/alarms")
    public List<Map<String, Object>> alarms(@PathVariable long homeId, CurrentUser user,
                                            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        Home h = ownHome(homeId, user);
        return jdbc.query("SELECT id, created_at, reason, details::text AS details, command_sent FROM alarms "
                        + "WHERE home_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("at", rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant().toString());
                    m.put("reason", rs.getString("reason"));
                    m.put("details", parse(rs.getString("details")));
                    m.put("command_sent", rs.getBoolean("command_sent"));
                    return m;
                }, h.id(), limit);
    }

    @GetMapping("/{homeId}/commands")
    public List<Map<String, Object>> commands(@PathVariable long homeId, CurrentUser user,
                                              @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        Home h = ownHome(homeId, user);
        return jdbc.query("SELECT id, created_at, relay_id, command, source, user_id, reason FROM relay_commands "
                        + "WHERE home_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("at", rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant().toString());
                    m.put("relay_id", rs.getString("relay_id"));
                    m.put("command", rs.getInt("command"));
                    m.put("source", rs.getString("source"));
                    long uid = rs.getLong("user_id");
                    m.put("user_id", rs.wasNull() ? null : uid);
                    m.put("reason", rs.getString("reason"));
                    return m;
                }, h.id(), limit);
    }

    private Object parse(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
