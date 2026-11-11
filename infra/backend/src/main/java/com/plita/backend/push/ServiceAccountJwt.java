package com.plita.backend.push;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * JWT-утверждение сервисного аккаунта Google (RS256) для обмена на OAuth-токен FCM.
 * Без внешних зависимостей: подпись средствами JDK.
 *
 * <pre>
 * POST token_uri  grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&amp;assertion=&lt;этот JWT&gt;
 * </pre>
 */
public final class ServiceAccountJwt {

    public static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private ServiceAccountJwt() {
    }

    public static String assertion(String clientEmail, PrivateKey key, String tokenUri, String scope, long nowS) {
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String claims = "{\"iss\":\"" + json(clientEmail) + "\",\"scope\":\"" + json(scope) + "\",\"aud\":\""
                + json(tokenUri) + "\",\"iat\":" + nowS + ",\"exp\":" + (nowS + 3600) + "}";
        String signingInput = b64(header.getBytes(StandardCharsets.UTF_8)) + "." + b64(claims.getBytes(StandardCharsets.UTF_8));
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("не удалось подписать JWT сервисного аккаунта", e);
        }
    }

    /** private_key из JSON ключа Firebase: PEM «-----BEGIN PRIVATE KEY-----» (PKCS#8). */
    public static PrivateKey parsePrivateKey(String pem) {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalArgumentException("private_key сервисного аккаунта не похож на PEM PKCS#8 RSA", e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
