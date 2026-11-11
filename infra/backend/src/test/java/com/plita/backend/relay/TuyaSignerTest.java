package com.plita.backend.relay;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ожидаемые подписи посчитаны независимо (Python hashlib/hmac) по алгоритму из документации Tuya. */
class TuyaSignerTest {

    private static final String ACCESS_ID = "4ehvfw4xs8ysv3s6fna9";
    private static final String SECRET = "7b2b4b4a5f6c4e1d8a9b0c1d2e3f4a5b";
    private static final long T = 1700000000000L;

    @Test
    void sha256AndHmac() {
        assertEquals(TuyaSigner.EMPTY_BODY_SHA256, TuyaSigner.sha256Hex(""));
        // RFC 4231 / известный пример HMAC-SHA256
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                TuyaSigner.hmacSha256Hex("The quick brown fox jumps over the lazy dog", "key"));
    }

    @Test
    void urlSortsQuery() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("page_size", "20");
        q.put("device_ids", "a,b");
        assertEquals("/v1.0/devices?device_ids=a,b&page_size=20", TuyaSigner.signUrl("/v1.0/devices", q));
        assertEquals("/v1.0/devices/x", TuyaSigner.signUrl("/v1.0/devices/x", Map.of()));
    }

    @Test
    void stringToSign() {
        assertEquals("GET\n" + TuyaSigner.EMPTY_BODY_SHA256 + "\n\n/v1.0/token?grant_type=1",
                TuyaSigner.stringToSign("get", "", "/v1.0/token?grant_type=1"));
    }

    @Test
    void tokenRequestSign() {
        String sts = TuyaSigner.stringToSign("GET", "", TuyaSigner.signUrl("/v1.0/token", Map.of("grant_type", "1")));
        assertEquals("A1C2C02BACF277DE441BAEC49F1A6FDBE4BE17005A9D066EE5266BA6F7CB8F2E",
                TuyaSigner.sign(ACCESS_ID, null, T, "", sts, SECRET));
    }

    @Test
    void businessRequestSign() {
        String body = "{\"commands\":[{\"code\":\"switch\",\"value\":false}]}";
        String sts = TuyaSigner.stringToSign("POST", body, "/v1.0/devices/bf123456789abcdefg/commands");
        assertEquals("POST\n8082799df2992787ad67e99f596599253d5b26b2981655d926244d056bdb668f\n\n"
                + "/v1.0/devices/bf123456789abcdefg/commands", sts);
        assertEquals("F3E694C23E09AAFE309A9F45A199060C5211CEB3024860C9D7CFB0A31CC81CC1",
                TuyaSigner.sign(ACCESS_ID, "abc123token", T, "", sts, SECRET));
    }
}
