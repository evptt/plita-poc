package com.plita.backend.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Проверка Google id_token (рис. 3, шаги 5-6): подпись по открытым ключам Google (RS256),
 * срок действия, audience = GOOGLE_CLIENT_ID (Web и/или Android, через запятую), издатель.
 */
public class GoogleTokenVerifier {

    public static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    public record GoogleUser(String googleId, String email, String name) {
    }

    private final List<String> clientIds;
    private final ConfigurableJWTProcessor<SecurityContext> processor;

    public GoogleTokenVerifier(List<String> clientIds) {
        this.clientIds = clientIds;
        if (clientIds.isEmpty()) {
            this.processor = null;
            return;
        }
        JWKSource<SecurityContext> keys;
        try {
            keys = JWKSourceBuilder.create(URI.create(GOOGLE_JWKS).toURL()).build(); // с кешем ключей
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                new HashSet<>(clientIds),                 // допустимые aud
                null,
                Set.of("sub", "iss", "exp", "iat"),       // обязательные поля
                null));
        this.processor = p;
    }

    public GoogleUser verify(String idToken) {
        if (processor == null) {
            throw new AuthException.Unavailable("GOOGLE_CLIENT_ID не задан на сервере");
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(idToken, null);
        } catch (KeySourceException e) {
            throw new AuthException.Unavailable("не удалось получить ключи Google: " + e.getMessage());
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new AuthException("Google id_token не прошёл проверку: " + e.getMessage());
        }
        if (!ISSUERS.contains(claims.getIssuer())) {
            throw new AuthException("неверный издатель Google id_token");
        }
        Object email = claims.getClaim("email");
        Object name = claims.getClaim("name");
        return new GoogleUser(claims.getSubject(),
                email == null ? null : email.toString(),
                name == null ? null : name.toString());
    }

    public List<String> clientIds() {
        return clientIds;
    }
}
