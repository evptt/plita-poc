package com.plita.backend.relay;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Подпись запросов Tuya OpenAPI (HMAC-SHA256). Без внешних зависимостей.
 *
 * <pre>
 * stringToSign = METHOD + "\n" + sha256(body) + "\n" + "\n" + URL      (заголовки в подпись не входят)
 * URL          = путь + "?" + параметры запроса, отсортированные по ключу
 * str          = client_id + [access_token] + t + nonce + stringToSign  (access_token нет только у /v1.0/token)
 * sign         = HMAC-SHA256(str, access_secret), hex в верхнем регистре
 * </pre>
 */
public final class TuyaSigner {

    public static final String EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private TuyaSigner() {
    }

    /** Путь с параметрами запроса в порядке, который требует подпись. Тот же URL уходит в сам запрос. */
    public static String signUrl(String path, Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(query).entrySet()) {
            if (!first) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    public static String stringToSign(String method, String body, String url) {
        return method.toUpperCase(Locale.ROOT) + "\n" + sha256Hex(body == null ? "" : body) + "\n\n" + url;
    }

    public static String sign(String accessId, String accessToken, long t, String nonce, String stringToSign,
                              String secret) {
        String msg = accessId + (accessToken == null ? "" : accessToken) + t + (nonce == null ? "" : nonce)
                + stringToSign;
        return hmacSha256Hex(msg, secret).toUpperCase(Locale.ROOT);
    }

    static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hmacSha256Hex(String msg, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
