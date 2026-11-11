package com.plita.backend.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plita.backend.config.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Push «красного экрана» через Firebase Cloud Messaging (HTTP v1).
 *
 * <p>Приложение регистрирует свой FCM-токен (POST /api/v1/push/tokens). При тревоге Engine вызывает
 * {@link #sendAlarm}; отправка идёт в отдельном потоке, чтобы сеть не задерживала обработку пакетов.
 * Токены, которые FCM называет недействительными, удаляются.
 *
 * <p>Ключ: Firebase Console -> Project settings -> Service accounts -> Generate new private key.
 * Его JSON (как есть или в base64) кладётся в FIREBASE_SERVICE_ACCOUNT. Без ключа push выключен,
 * WebSocket-события продолжают работать.
 */
@Service
public class PushService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    public record Result(int sent, int failed, int removed) {
    }

    record Account(String projectId, String clientEmail, PrivateKey key, String tokenUri) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String androidChannelId;
    private final Account account;
    private final HttpClient http;
    private final ExecutorService sender;

    private String accessToken;
    private long accessTokenValidUntilMs;

    public PushService(JdbcTemplate jdbc, ObjectMapper mapper, AppSettings settings) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.androidChannelId = settings.push().androidChannelId();
        Account acc = null;
        if (settings.push().configured()) {
            try {
                acc = parseAccount(mapper, settings.push().serviceAccount());
            } catch (IllegalArgumentException e) {
                // битый ключ не должен ронять весь бэкенд: тревоги продолжат уходить по WebSocket
                log.error("Push выключен: {}", e.getMessage());
            }
        }
        this.account = acc;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.sender = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "push-sender");
            t.setDaemon(true);
            return t;
        });
        if (account != null) {
            log.info("Push: Firebase-проект {}, сервисный аккаунт {}", account.projectId(), account.clientEmail());
        }
    }

    public boolean enabled() {
        return account != null;
    }

    /** JSON ключа сервисного аккаунта как есть или в base64. Бросает IllegalArgumentException, если ключ битый. */
    static Account parseAccount(ObjectMapper mapper, String raw) {
        String json = raw.trim();
        if (!json.startsWith("{")) {
            try {
                json = new String(Base64.getMimeDecoder().decode(json), StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("FIREBASE_SERVICE_ACCOUNT: ни JSON, ни base64 от JSON");
            }
        }
        JsonNode n;
        try {
            n = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("FIREBASE_SERVICE_ACCOUNT: битый JSON ключа сервисного аккаунта");
        }
        String projectId = n.path("project_id").asText("");
        String email = n.path("client_email").asText("");
        String pem = n.path("private_key").asText("");
        if (projectId.isEmpty() || email.isEmpty() || pem.isEmpty()) {
            throw new IllegalArgumentException("FIREBASE_SERVICE_ACCOUNT: нужны project_id, client_email и private_key");
        }
        return new Account(projectId, email, ServiceAccountJwt.parsePrivateKey(pem),
                n.path("token_uri").asText("https://oauth2.googleapis.com/token"));
    }

    // ------------------------------------------------------------------ отправка

    /** Тревога: push на все устройства владельца Дома. Не блокирует вызывающего. */
    public void sendAlarm(long userId, long homeId, String homeName, String reason, boolean commandSent, String at) {
        if (!enabled()) {
            return;
        }
        sender.execute(() -> {
            try {
                Result r = sendToUser(userId, token -> FcmMessages.alarm(token, homeId, homeName, reason,
                        commandSent, at, androidChannelId));
                log.info("Push о тревоге (Дом {}, {}): отправлено {}, ошибок {}, удалено токенов {}",
                        homeId, reason, r.sent(), r.failed(), r.removed());
            } catch (RuntimeException e) {
                log.error("Push о тревоге (Дом {}) не отправлен: {}", homeId, e.getMessage());
            }
        });
    }

    /** Проверочный push на устройства пользователя; синхронно, для POST /api/v1/push/test. */
    public Result sendTest(long userId) {
        return sendToUser(userId, token -> FcmMessages.test(token, androidChannelId));
    }

    private Result sendToUser(long userId, java.util.function.Function<String, Map<String, Object>> build) {
        List<String> tokens = jdbc.queryForList("SELECT token FROM push_tokens WHERE user_id = ?", String.class, userId);
        int sent = 0;
        int failed = 0;
        int removed = 0;
        for (String token : tokens) {
            switch (sendOne(build.apply(token))) {
                case OK -> sent++;
                case INVALID_TOKEN -> {
                    jdbc.update("DELETE FROM push_tokens WHERE token = ?", token);
                    removed++;
                }
                case ERROR -> failed++;
            }
        }
        return new Result(sent, failed, removed);
    }

    private enum Outcome { OK, INVALID_TOKEN, ERROR }

    private Outcome sendOne(Map<String, Object> message) {
        String body;
        try {
            body = mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                        "https://fcm.googleapis.com/v1/projects/" + account.projectId() + "/messages:send"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = send(req);
        int code = resp.statusCode();
        if (code == 200) {
            return Outcome.OK;
        }
        String text = resp.body() == null ? "" : resp.body();
        // токен удалён приложением/устарел: 404 UNREGISTERED или 400 с жалобой на registration token
        if (code == 404 || (code == 400 && text.contains("registration token"))) {
            return Outcome.INVALID_TOKEN;
        }
        if (code == 401 || code == 403) {
            dropAccessToken();
        }
        log.warn("FCM ответил {}: {}", code, text.length() > 300 ? text.substring(0, 300) : text);
        return Outcome.ERROR;
    }

    private synchronized String accessToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < accessTokenValidUntilMs) {
            return accessToken;
        }
        String assertion = ServiceAccountJwt.assertion(account.clientEmail(), account.key(), account.tokenUri(),
                ServiceAccountJwt.FCM_SCOPE, now / 1000);
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(account.tokenUri()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = send(req);
        try {
            JsonNode n = mapper.readTree(resp.body());
            String token = n.path("access_token").asText("");
            if (resp.statusCode() != 200 || token.isEmpty()) {
                throw new IllegalStateException("Google OAuth не выдал токен для FCM: HTTP " + resp.statusCode()
                        + " " + n.path("error_description").asText(n.path("error").asText("")));
            }
            accessToken = token;
            accessTokenValidUntilMs = now + Math.max(60, n.path("expires_in").asLong(3600) - 60) * 1000L;
            return accessToken;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Google OAuth вернул не JSON: HTTP " + resp.statusCode());
        }
    }

    private synchronized void dropAccessToken() {
        accessToken = null;
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("нет связи с " + req.uri().getHost() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("отправка push прервана", e);
        }
    }

    @Override
    public void destroy() {
        sender.shutdownNow();
    }
}
