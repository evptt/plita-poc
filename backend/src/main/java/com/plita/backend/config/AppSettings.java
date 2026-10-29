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
 * DATABASE_URL, MQTT_HOST, MQTT_PORT, MQTT_USER, MQTT_PASSWORD, JWT_SECRET, GOOGLE_CLIENT_ID.
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
        String analysisParamsPath) {

    private static final Logger LOG = Logger.getLogger(AppSettings.class.getName());

    public record Db(String jdbcUrl, String user, String password) {
    }

    public record SeedDevice(String id, String type) {
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
                get(env, "ANALYSIS_PARAMS_PATH", ""));
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
    }
}
