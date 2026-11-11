package com.plita.backend.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Настройки сервиса из переменных окружения.
 *
 * <p>Инфраструктурные переменные приходят из docker-compose (см. отчёт по инфраструктуре):
 * DATABASE_URL, MQTT_HOST, MQTT_PORT, MQTT_USER, MQTT_PASSWORD, JWT_SECRET, GOOGLE_CLIENT_ID,
 * TUYA_* (автомат через Tuya Cloud), FIREBASE_* (push).
 * Остальные необязательны и имеют безопасные значения по умолчанию.
 */
public record AppSettings(
        Db db,
        String mqttHost,
        int mqttPort,
        String mqttUser,
        String mqttPassword,
        String mqttClientId,
        String jwtSecret,
        boolean jwtSecretGenerated,
        int jwtTtlHours,
        List<String> googleClientIds,
        boolean devAuthEnabled,
        List<SeedDevice> seedDevices,
        String sensorByteOrder,
        String firmwareLatestVersion,
        String analysisParamsPath,
        Tuya tuya,
        Push push) {

    private static final Logger LOG = Logger.getLogger(AppSettings.class.getName());

    public record Db(String jdbcUrl, String user, String password) {
    }

    public record SeedDevice(String id, String type) {
    }

    /**
     * Tuya Cloud OpenAPI: силовой автомат Tongou на заводской прошивке Tuya.
     * Ключи берутся в облачном проекте на platform.tuya.com (Cloud -> проект -> Overview: Access ID / Access Secret),
     * endpoint - дата-центр проекта (Central Europe: https://openapi.tuyaeu.com).
     */
    public record Tuya(String endpoint, String accessId, String accessSecret, double pollIntervalS,
                       String switchCode, String currentCode, double currentScale,
                       String voltageCode, double voltageScale) {

        public boolean enabled() {
            return !accessId.isEmpty() && !accessSecret.isEmpty();
        }

        /** Сторож связи: автомат считается пропавшим после трёх пропущенных опросов и ещё 30 с. */
        public double relayTimeoutS() {
            return 3 * pollIntervalS + 30;
        }
    }

    /**
     * Push через Firebase Cloud Messaging. serviceAccount - JSON ключа сервисного аккаунта Firebase
     * (как есть или в base64), androidChannelId - канал уведомлений в Android-приложении (необязательно).
     */
    public record Push(String serviceAccount, String androidChannelId) {

        public boolean configured() {
            return !serviceAccount.isEmpty();
        }
    }

    public static AppSettings fromEnv(Map<String, String> env) {
        String jwtSecret = get(env, "JWT_SECRET", "");
        boolean generated = false;
        if (jwtSecret.isEmpty()) {
            byte[] b = new byte[48];
            new SecureRandom().nextBytes(b);
            jwtSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            generated = true;
        }

        List<SeedDevice> seed = new ArrayList<>();
        for (String item : get(env, "SEED_DEVICES", "emu-sensor-1:sensor,emu-relay-1:relay").split(",")) {
            item = item.trim();
            if (item.isEmpty()) {
                continue;
            }
            int i = item.indexOf(':');
            String type = i < 0 ? "" : item.substring(i + 1).trim();
            if (!type.equals("sensor") && !type.equals("relay")) {
                throw new IllegalArgumentException("SEED_DEVICES: неверный тип в '" + item + "', нужно sensor или relay");
            }
            seed.add(new SeedDevice(item.substring(0, i).trim(), type));
        }

        String byteOrder = get(env, "SENSOR_BYTE_ORDER", "little").toLowerCase();
        if (!byteOrder.equals("little") && !byteOrder.equals("big")) {
            throw new IllegalArgumentException("SENSOR_BYTE_ORDER должен быть little или big");
        }

        List<String> clientIds = Arrays.stream(get(env, "GOOGLE_CLIENT_ID", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        double pollS = number(env, "TUYA_POLL_INTERVAL_S", 120);
        if (pollS < 10) {
            throw new IllegalArgumentException("TUYA_POLL_INTERVAL_S не меньше 10 секунд (квота Tuya IoT Core)");
        }
        Tuya tuya = new Tuya(
                get(env, "TUYA_ENDPOINT", "https://openapi.tuyaeu.com"),
                get(env, "TUYA_ACCESS_ID", ""),
                get(env, "TUYA_ACCESS_SECRET", ""),
                pollS,
                get(env, "TUYA_SWITCH_CODE", "switch"),
                get(env, "TUYA_CURRENT_CODE", "cur_current"),
                number(env, "TUYA_CURRENT_SCALE", 0.001),
                get(env, "TUYA_VOLTAGE_CODE", "cur_voltage"),
                number(env, "TUYA_VOLTAGE_SCALE", 0.1));

        Push push = new Push(get(env, "FIREBASE_SERVICE_ACCOUNT", ""), get(env, "FIREBASE_ANDROID_CHANNEL_ID", ""));

        return new AppSettings(
                parseDatabaseUrl(get(env, "DATABASE_URL", "postgresql://plita:plita@localhost:5432/plita")),
                get(env, "MQTT_HOST", "localhost"),
                Integer.parseInt(get(env, "MQTT_PORT", "1883")),
                get(env, "MQTT_USER", ""),
                get(env, "MQTT_PASSWORD", ""),
                get(env, "MQTT_CLIENT_ID", "plita-backend-" + get(env, "HOSTNAME", "local")),
                jwtSecret,
                generated,
                Integer.parseInt(get(env, "JWT_TTL_HOURS", String.valueOf(24 * 30))),
                clientIds,
                bool(get(env, "DEV_AUTH_ENABLED", "false")),
                List.copyOf(seed),
                byteOrder,
                get(env, "FIRMWARE_LATEST_VERSION", "0.0.0"),
                get(env, "ANALYSIS_PARAMS_PATH", ""),
                tuya,
                push);
    }

    private static double number(Map<String, String> env, String name, double def) {
        String v = get(env, name, "");
        if (v.isEmpty()) {
            return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " должно быть числом, а не '" + v + "'");
        }
    }

    /**
     * DATABASE_URL приходит в формате postgresql://user:pass@host:port/db (как в docker-compose),
     * JDBC ждёт jdbc:postgresql://host:port/db и отдельно логин/пароль.
     */
    public static Db parseDatabaseUrl(String url) {
        if (url.startsWith("jdbc:")) {
            return new Db(url, null, null);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("DATABASE_URL не похож на postgresql://user:pass@host:port/db", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("postgresql") || scheme.equals("postgres"))) {
            throw new IllegalArgumentException("DATABASE_URL: ожидается схема postgresql://");
        }
        String user = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int i = userInfo.indexOf(':');
            user = decode(i < 0 ? userInfo : userInfo.substring(0, i));
            password = i < 0 ? null : decode(userInfo.substring(i + 1));
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/postgres" : uri.getRawPath();
        String jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + port + path
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        return new Db(jdbc, user, password);
    }

    private static String decode(String s) {
        // как urllib.parse.unquote: '+' в пароле остаётся плюсом, а не пробелом
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String get(Map<String, String> env, String name, String def) {
        String v = env.get(name);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static boolean bool(String v) {
        return switch (v.toLowerCase()) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    public void logWarnings() {
        if (jwtSecretGenerated) {
            LOG.warning("JWT_SECRET не задан: сгенерирован временный, токены сбросятся при перезапуске");
        }
        if (googleClientIds.isEmpty()) {
            LOG.warning("GOOGLE_CLIENT_ID пуст: POST /api/v1/auth/google будет отвечать 503");
        }
        if (!tuya.enabled()) {
            LOG.warning("TUYA_ACCESS_ID / TUYA_ACCESS_SECRET пусты: автомат Tuya не опрашивается, реле - только эмулятор через MQTT");
        }
        if (!push.configured()) {
            LOG.warning("FIREBASE_SERVICE_ACCOUNT пуст: push о тревоге не отправляются (WebSocket работает)");
        }
    }
}
