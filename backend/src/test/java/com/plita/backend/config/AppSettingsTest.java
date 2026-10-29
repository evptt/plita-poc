package com.plita.backend.config;

import com.plita.backend.config.AppSettings.Db;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSettingsTest {

    @Test
    void databaseUrlFromCompose() {
        Db db = AppSettings.parseDatabaseUrl("postgresql://plita:p%40ss:word@db:5432/plita");
        assertEquals("jdbc:postgresql://db:5432/plita", db.jdbcUrl());
        assertEquals("plita", db.user());
        assertEquals("p@ss:word", db.password());
    }

    @Test
    void databaseUrlDefaultsAndJdbcPassthrough() {
        assertEquals("jdbc:postgresql://h:5432/x", AppSettings.parseDatabaseUrl("postgres://u@h/x").jdbcUrl());
        assertEquals("jdbc:postgresql://a/b", AppSettings.parseDatabaseUrl("jdbc:postgresql://a/b").jdbcUrl());
        assertThrows(IllegalArgumentException.class, () -> AppSettings.parseDatabaseUrl("mysql://a/b"));
        assertEquals("a+b", AppSettings.parseDatabaseUrl("postgresql://u:a+b@h:1/x").password());
    }

    @Test
    void defaults() {
        AppSettings s = AppSettings.fromEnv(Map.of());
        assertEquals(List.of(new AppSettings.SeedDevice("emu-sensor-1", "sensor"),
                new AppSettings.SeedDevice("emu-relay-1", "relay")), s.seedDevices());
        assertEquals("little", s.sensorByteOrder());
        assertFalse(s.devAuthEnabled());
        assertTrue(s.jwtSecretGenerated());
        assertTrue(s.googleClientIds().isEmpty());
        assertEquals(1883, s.mqttPort());
    }

    @Test
    void customValues() {
        Map<String, String> env = new HashMap<>();
        env.put("GOOGLE_CLIENT_ID", " web.apps , android.apps ");
        env.put("DEV_AUTH_ENABLED", "true");
        env.put("JWT_SECRET", "x");
        env.put("SENSOR_BYTE_ORDER", "BIG");
        AppSettings s = AppSettings.fromEnv(env);
        assertEquals(List.of("web.apps", "android.apps"), s.googleClientIds());
        assertTrue(s.devAuthEnabled());
        assertFalse(s.jwtSecretGenerated());
        assertEquals("big", s.sensorByteOrder());
    }

    @Test
    void badValues() {
        assertThrows(IllegalArgumentException.class, () -> AppSettings.fromEnv(Map.of("SENSOR_BYTE_ORDER", "middle")));
        assertThrows(IllegalArgumentException.class, () -> AppSettings.fromEnv(Map.of("SEED_DEVICES", "a:lamp")));
    }
}
