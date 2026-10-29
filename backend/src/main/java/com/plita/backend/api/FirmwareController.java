package com.plita.backend.api;

import com.plita.backend.config.AppSettings;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Заглушки OTA (раздел 10 разбора ТЗ: реальный OTA в PoC не делаем). */
@RestController
@RequestMapping("/api/v1/firmware")
public class FirmwareController {

    private final JdbcTemplate jdbc;
    private final AppSettings settings;

    public FirmwareController(JdbcTemplate jdbc, AppSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @GetMapping("/check")
    public Map<String, Object> check(@RequestParam(name = "device_id", required = false) String deviceId,
                                     @RequestParam(name = "current_version", required = false) String currentVersion) {
        String current = currentVersion;
        if (current == null && deviceId != null) {
            List<String> v = jdbc.queryForList("SELECT firmware_version FROM devices WHERE id = ?", String.class, deviceId);
            current = v.isEmpty() ? null : v.get(0);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("device_id", deviceId);
        m.put("current_version", current);
        m.put("latest_version", settings.firmwareLatestVersion());
        m.put("update_available", false);
        m.put("download_url", null);
        m.put("stub", true);
        return m;
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam(name = "device_id", required = false) String deviceId,
                                           @RequestParam(name = "version", required = false) String version) {
        String v = version != null ? version : settings.firmwareLatestVersion();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Firmware-Version", v)
                .header("X-Firmware-Stub", "true")
                .header("Content-Disposition", "attachment; filename=\"firmware-" + v.replaceAll("[^A-Za-z0-9._-]", "_") + ".bin\"")
                .body(new byte[0]);
    }
}
