package com.plita.backend.config;

import com.plita.backend.PlitaApplication;
import com.plita.backend.engine.Engine;
import com.plita.backend.ingest.MqttService;
import com.plita.backend.relay.TuyaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Порядок старта (миграции Flyway к этому моменту уже применены):
 * проверка схемы -> заведение устройств эмулятора -> загрузка Engine -> подключение к MQTT -> опрос Tuya.
 */
@Component
public class Startup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Startup.class);

    static final Map<String, Set<String>> REQUIRED_COLUMNS = new LinkedHashMap<>();

    static {
        REQUIRED_COLUMNS.put("users", Set.of("id", "google_id", "email", "name", "created_at"));
        REQUIRED_COLUMNS.put("homes", Set.of("id", "user_id", "name"));
        REQUIRED_COLUMNS.put("devices", Set.of("id", "home_id", "type", "mac_address", "firmware_version"));
        REQUIRED_COLUMNS.put("telemetry_history",
                Set.of("id", "device_id", "timestamp", "val_1", "val_2", "val_3", "val_4"));
    }

    private final JdbcTemplate jdbc;
    private final AppSettings settings;
    private final Engine engine;
    private final MqttService mqtt;
    private final TuyaService tuya;

    public Startup(JdbcTemplate jdbc, AppSettings settings, Engine engine, MqttService mqtt, TuyaService tuya) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.engine = engine;
        this.mqtt = mqtt;
        this.tuya = tuya;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Старт бэкенда {}", PlitaApplication.VERSION);
        settings.logWarnings();
        checkSchema();
        seedDevices();
        engine.load();
        mqtt.start(engine::handleMessage);
        tuya.start(engine::tuyaRelayIds, engine::handleTuyaReading);
    }

    /** Падаем сразу и понятно, если колонки в базе называются не так, как ждёт код. */
    void checkSchema() {
        Map<String, Set<String>> present = new HashMap<>();
        jdbc.query("SELECT table_name, column_name FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name IN ('users','homes','devices','telemetry_history')",
                rs -> {
                    present.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
                });
        StringBuilder problems = new StringBuilder();
        REQUIRED_COLUMNS.forEach((table, cols) -> {
            Set<String> missing = new HashSet<>(cols);
            missing.removeAll(present.getOrDefault(table, Set.of()));
            if (!missing.isEmpty()) {
                problems.append(table).append(": нет колонок ").append(missing.stream().sorted().toList()).append("; ");
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Схема БД не совпадает с ожидаемой (раздел 5 ТЗ): " + problems);
        }
    }

    /** Заводим ID эмулятора, чтобы его данные писались в историю (см. отчёт по инфраструктуре). Эмулятор - всегда MQTT. */
    void seedDevices() {
        List<AppSettings.SeedDevice> seed = settings.seedDevices();
        for (AppSettings.SeedDevice d : seed) {
            jdbc.update("INSERT INTO devices (id, type, platform) VALUES (?, ?, 'mqtt') ON CONFLICT (id) DO NOTHING",
                    d.id(), d.type());
        }
        if (!seed.isEmpty()) {
            log.info("Устройства заведены (если их не было): {}",
                    String.join(", ", seed.stream().map(AppSettings.SeedDevice::id).toList()));
        }
    }
}
