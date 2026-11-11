package com.plita.backend.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Внутренний сессионный JWT (HS256) после входа через Google. */
public class JwtService {

    public static final String ISSUER = "plita-backend";

    public record IssuedToken(String token, long expiresInSeconds) {
    }

    private final byte[] key;
    private final Duration ttl;

    public JwtService(String secret, int ttlHours) {
        // HS256 требует ключ >= 256 бит: берём SHA-256 от JWT_SECRET, чтобы подошла строка любой длины
        try {
            this.key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        this.ttl = Duration.ofHours(ttlHours);
    }

    public IssuedToken issue(long userId) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(Long.toString(userId))
                .issuer(ISSUER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("не удалось подписать JWT", e);
        }
        return new IssuedToken(jwt.serialize(), ttl.toSeconds());
    }

    /** Возвращает id пользователя или бросает AuthException. */
    public long verify(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthException("нужен заголовок Authorization: Bearer <JWT>");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(key))) {
                throw new AuthException("недействительный или просроченный токен");
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            Date exp = c.getExpirationTime();
            if (exp == null || exp.toInstant().isBefore(Instant.now()) || !ISSUER.equals(c.getIssuer())
                    || c.getSubject() == null) {
                throw new AuthException("недействительный или просроченный токен");
            }
            return Long.parseLong(c.getSubject());
        } catch (ParseException | JOSEException | NumberFormatException e) {
            throw new AuthException("недействительный или просроченный токен");
        }
    }
}
