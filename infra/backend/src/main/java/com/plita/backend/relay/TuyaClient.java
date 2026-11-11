package com.plita.backend.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клиент Tuya Cloud OpenAPI (облачный проект на platform.tuya.com, сервис IoT Core).
 *
 * <ul>
 *   <li>токен: GET /v1.0/token?grant_type=1, живёт expire_time секунд, обновляется за минуту до конца;</li>
 *   <li>детали и статус устройства: GET /v1.0/devices/{id} - online и список status [{code, value}];</li>
 *   <li>команда: POST /v1.0/devices/{id}/commands {"commands": [{"code": "switch", "value": false}]}.</li>
 * </ul>
 * Каждый вызов тратит квоту IoT Core (на пробном тарифе её мало), поэтому опрос редкий.
 */
public class TuyaClient {

    private static final String TOKEN_PATH = "/v1.0/token";
    /** 1010 - токен недействителен, 1011 - токен истёк: получаем новый и повторяем запрос один раз. */
    private static final List<Integer> TOKEN_ERRORS = List.of(1010, 1011);

    /** Состояние устройства в облаке Tuya. */
    public record DeviceState(boolean online, Map<String, Object> status) {
    }

    private final String endpoint;
    private final String accessId;
    private final String accessSecret;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private String token;
    private long tokenValidUntilMs;

    public TuyaClient(String endpoint, String accessId, String accessSecret, ObjectMapper mapper) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.accessId = accessId;
        this.accessSecret = accessSecret;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public DeviceState device(String deviceId) {
        JsonNode r = call("GET", "/v1.0/devices/" + deviceId, null, null, true);
        Map<String, Object> status = new LinkedHashMap<>();
        for (JsonNode item : r.path("status")) {
            String code = item.path("code").asText(null);
            if (code != null) {
                status.put(code, plain(item.get("value")));
            }
        }
        return new DeviceState(r.path("online").asBoolean(true), status);
    }

    public void sendCommand(String deviceId, String code, Object value) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("code", code);
        cmd.put("value", value);
        JsonNode r = call("POST", "/v1.0/devices/" + deviceId + "/commands", null,
                Map.of("commands", List.of(cmd)), true);
        if (r.isBoolean() && !r.booleanValue()) {
            throw new TuyaException(0, "Tuya не приняла команду " + code + "=" + value + " для " + deviceId);
        }
    }

    // ------------------------------------------------------------------ транспорт

    private synchronized String token() {
        long now = System.currentTimeMillis();
        if (token != null && now < tokenValidUntilMs) {
            return token;
        }
        JsonNode r = call("GET", TOKEN_PATH, Map.of("grant_type", "1"), null, false);
        String t = r.path("access_token").asText(null);
        if (t == null || t.isEmpty()) {
            throw new TuyaException(0, "Tuya не вернула access_token");
        }
        long ttlS = r.path("expire_time").asLong(r.path("expire").asLong(3600));
        token = t;
        tokenValidUntilMs = now + Math.max(60, ttlS - 60) * 1000L;
        return token;
    }

    private synchronized void dropToken() {
        token = null;
    }

    private JsonNode call(String method, String path, Map<String, String> query, Object body, boolean retry) {
        boolean tokenRequest = path.equals(TOKEN_PATH);
        String accessToken = tokenRequest ? null : token();
        String bodyText;
        try {
            bodyText = body == null ? "" : mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        String url = TuyaSigner.signUrl(path, query);
        long t = System.currentTimeMillis();
        String sign = TuyaSigner.sign(accessId, accessToken, t, "", TuyaSigner.stringToSign(method, bodyText, url),
                accessSecret);

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint + url))
                .timeout(Duration.ofSeconds(8))
                .header("client_id", accessId)
                .header("sign", sign)
                .header("t", Long.toString(t))
                .header("sign_method", "HMAC-SHA256")
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(bodyText));
        if (accessToken != null) {
            b.header("access_token", accessToken);
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TuyaException("Tuya Cloud недоступна: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TuyaException("запрос к Tuya прерван", e);
        }

        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (JsonProcessingException e) {
            throw new TuyaException(-1, "Tuya вернула не JSON (HTTP " + resp.statusCode() + ")");
        }
        if (root != null && root.path("success").asBoolean(false)) {
            return root.path("result");
        }
        int code = root == null ? resp.statusCode() : root.path("code").asInt(resp.statusCode());
        String msg = root == null ? "" : root.path("msg").asText("");
        if (retry && !tokenRequest && TOKEN_ERRORS.contains(code)) {
            dropToken();
            return call(method, path, query, body, false);
        }
        throw new TuyaException(code, "Tuya " + method + " " + path + ": " + code + " " + msg);
    }

    /** JSON-значение статуса -> Boolean / Long / Double / String. */
    private static Object plain(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isIntegralNumber()) {
            return v.longValue();
        }
        if (v.isNumber()) {
            return v.doubleValue();
        }
        if (v.isTextual()) {
            return v.textValue();
        }
        return v.toString();
    }
}
