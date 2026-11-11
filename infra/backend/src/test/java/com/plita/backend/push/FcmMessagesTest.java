package com.plita.backend.push;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FcmMessagesTest {

    @Test
    @SuppressWarnings("unchecked")
    void alarmMessage() {
        Map<String, Object> root = FcmMessages.alarm("tok", 7, "Кухня", "dangerous_dynamics", true,
                "2026-11-20T10:00:00Z", "alarms");
        Map<String, Object> m = (Map<String, Object>) root.get("message");
        assertEquals("tok", m.get("token"));

        Map<String, Object> n = (Map<String, Object>) m.get("notification");
        assertEquals("ТРЕВОГА: Кухня", n.get("title"));
        assertTrue(((String) n.get("body")).contains("Плита выключена"));

        // FCM принимает в data только строки
        Map<String, Object> data = (Map<String, Object>) m.get("data");
        assertEquals("alarm", data.get("type"));
        assertEquals("7", data.get("home_id"));
        assertEquals("true", data.get("command_sent"));
        data.values().forEach(v -> assertTrue(v instanceof String));

        Map<String, Object> android = (Map<String, Object>) m.get("android");
        assertEquals("HIGH", android.get("priority"));
        assertEquals("alarms", ((Map<String, Object>) android.get("notification")).get("channel_id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noChannelWhenNotConfigured() {
        Map<String, Object> m = (Map<String, Object>) FcmMessages.test("tok", "").get("message");
        Map<String, Object> an = (Map<String, Object>) ((Map<String, Object>) m.get("android")).get("notification");
        assertFalse(an.containsKey("channel_id"));
    }

    @Test
    void texts() {
        assertEquals("ТРЕВОГА", FcmMessages.alarmTitle(" "));
        assertTrue(FcmMessages.alarmBody("radar_critical", false).contains("НЕ выключена"));
        assertTrue(FcmMessages.alarmBody("something_new", true).startsWith("Опасная ситуация"));
    }
}
