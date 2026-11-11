package com.plita.backend.push;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceAccountJwtTest {

    private static String pem(PrivateKey key, boolean escapedNewlines) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
        return escapedNewlines ? pem.replace("\n", "\\n") : pem;
    }

    @Test
    void signedAssertionVerifiesWithPublicKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();

        // в JSON ключа Firebase переводы строк в private_key записаны как \n
        PrivateKey parsed = ServiceAccountJwt.parsePrivateKey(pem(kp.getPrivate(), true));
        assertEquals(kp.getPrivate(), ServiceAccountJwt.parsePrivateKey(pem(kp.getPrivate(), false)));

        String jwt = ServiceAccountJwt.assertion("svc@proj.iam.gserviceaccount.com", parsed,
                "https://oauth2.googleapis.com/token", ServiceAccountJwt.FCM_SCOPE, 1_700_000_000L);
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(kp.getPublic());
        sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(sig.verify(Base64.getUrlDecoder().decode(parts[2])));

        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertEquals("{\"alg\":\"RS256\",\"typ\":\"JWT\"}", header);
        assertEquals("{\"iss\":\"svc@proj.iam.gserviceaccount.com\",\"scope\":\""
                + ServiceAccountJwt.FCM_SCOPE + "\",\"aud\":\"https://oauth2.googleapis.com/token\","
                + "\"iat\":1700000000,\"exp\":1700003600}", claims);
    }

    @Test
    void badKey() {
        assertThrows(IllegalArgumentException.class, () -> ServiceAccountJwt.parsePrivateKey("not a key"));
    }
}
