package com.plita.backend.api;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET /api/v1/analytics - агрегированные точки для графиков (раздел 9 разбора ТЗ). */
@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    static final Duration MAX_SPAN = Duration.ofDays(31);
    static final int MAX_POINTS = 2000;
    static final int DEFAULT_POINTS = 300;

    /** Раскладка val_1..val_4 по типам устройств (таблица из раздела 4 разбора ТЗ). */
    static final Map<String, String[]> FIELDS = Map.of(
            "sensor", new String[]{"temperature", "humidity", "voc", "radar"},
            "relay", new String[]{"voltage", "current", null, "state"});

    private final JdbcTemplate jdbc;

    public AnalyticsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Средние значения за каждый интервал (радар и статус реле - максимум за интервал,
     * т.е. «было ли движение / было ли включено»).
     *
     * @param homeId     Дом пользователя (вместе с device_type)
     * @param deviceType sensor (по умолчанию) или relay
     * @param deviceId   вместо home_id можно указать устройство напрямую
     * @param from       ISO 8601, по умолчанию час назад
     * @param to         ISO 8601, по умолчанию сейчас
     * @param step       шаг агрегации, секунды
     * @param points     желаемое число точек, если step не задан
     */
    @GetMapping("/analytics")
    public Map<String, Object> analytics(CurrentUser user,
                                         @RequestParam(name = "home_id", required = false) Long homeId,
                                         @RequestParam(name = "device_type", defaultValue = "sensor") String deviceType,
                                         @RequestParam(name = "device_id", required = false) String deviceId,
                                         @RequestParam(name = "from", required = false) String from,
                                         @RequestParam(name = "to", required = false) String to,
                                         @RequestParam(name = "step", required = false) Integer step,
                                         @RequestParam(name = "points", defaultValue = "" + DEFAULT_POINTS) int points) {
        if (!deviceType.equals("sensor") && !deviceType.equals("relay")) {
            throw ApiErrors.error(HttpStatus.UNPROCESSABLE_ENTITY, "device_type: sensor или relay");
        }
        if (step != null && (step < 1 || step > 86400)) {
            throw ApiErrors.error(HttpStatus.UNPROCESSABLE_ENTITY, "step: от 1 до 86400 секунд");
        }
        if (points < 10 || points > MAX_POINTS) {
            throw ApiErrors.error(HttpStatus.UNPROCESSABLE_ENTITY, "points: от 10 до " + MAX_POINTS);
        }
        if (deviceId == null && homeId == null) {
            throw ApiErrors.error(HttpStatus.UNPROCESSABLE_ENTITY, "нужен home_id или device_id");
        }

        List<Map<String, Object>> devs = deviceId != null
                ? jdbc.queryForList("SELECT d.id, d.type::text AS type, d.home_id FROM devices d "
                + "JOIN homes h ON h.id = d.home_id WHERE d.id = ? AND h.user_id = ?", deviceId, user.id())
                : jdbc.queryForList("SELECT d.id, d.type::text AS type, d.home_id FROM devices d "
                + "JOIN homes h ON h.id = d.home_id WHERE h.id = ? AND h.user_id = ? AND d.type::text = ?",
                homeId, user.id(), deviceType);
        if (devs.isEmpty()) {
            throw ApiErrors.error(HttpStatus.NOT_FOUND, "устройство не найдено среди ваших Домов");
        }
        Map<String, Object> dev = devs.get(0);

        Instant tTo = to != null ? parseTime(to, "to") : Instant.now();
        Instant tFrom = from != null ? parseTime(from, "from") : tTo.minus(Duration.ofHours(1));
        if (!tFrom.isBefore(tTo)) {
            throw ApiErrors.error(HttpStatus.UNPROCESSABLE_ENTITY, "from должен быть раньше to");
        }
        if (Duration.between(tFrom, tTo).compareTo(MAX_SPAN) > 0) {
            throw ApiErrors.error(HttpStatus.UNPROCESSABLE_ENTITY, "период не больше 31 дня");
        }

        double spanS = Duration.between(tFrom, tTo).toMillis() / 1000.0;
        int stepS = step != null ? step : Math.max(1, (int) Math.ceil(spanS / points));
        if (spanS / stepS > MAX_POINTS) {
            stepS = (int) Math.ceil(spanS / MAX_POINTS);
        }

        String type = (String) dev.get("type");
        String[] names = FIELDS.get(type);
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("""
                SELECT date_bin(make_interval(secs => ?), "timestamp"::timestamptz,
                                TIMESTAMPTZ '2000-01-01 00:00:00+00') AS bucket,
                       avg(val_1) AS v1, avg(val_2) AS v2, avg(val_3) AS v3, max(val_4) AS v4,
                       count(*) AS n
                FROM telemetry_history
                WHERE device_id = ? AND "timestamp" >= ?::timestamptz AND "timestamp" < ?::timestamptz
                GROUP BY bucket ORDER BY bucket""",
                rs -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("t", rs.getObject("bucket", OffsetDateTime.class).toInstant().toString());
                    p.put("n", rs.getLong("n"));
                    String[] keys = {"v1", "v2", "v3", "v4"};
                    for (int i = 0; i < 4; i++) {
                        if (names[i] == null) {
                            continue;
                        }
                        double v = rs.getDouble(keys[i]);
                        if (rs.wasNull()) {
                            p.put(names[i], null);
                        } else if (names[i].equals("radar") || names[i].equals("state")) {
                            p.put(names[i], (int) v);
                        } else {
                            p.put(names[i], Math.round(v * 1e4) / 1e4);
                        }
                    }
                    out.add(p);
                },
                (double) stepS, dev.get("id"), OffsetDateTime.ofInstant(tFrom, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(tTo, ZoneOffset.UTC));

        List<String> fields = new ArrayList<>();
        for (String n : names) {
            if (n != null) {
                fields.add(n);
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("device_id", dev.get("id"));
        res.put("device_type", type);
        res.put("home_id", dev.get("home_id"));
        res.put("from", tFrom.toString());
        res.put("to", tTo.toString());
        res.put("step_s", stepS);
        res.put("fields", fields);
        res.put("points", out);
        return res;
    }

    /** ISO 8601 со смещением или без (тогда считаем UTC). */
    static Instant parseTime(String s, String field) {
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                throw ApiErrors.error(HttpStatus.UNPROCESSABLE_ENTITY, field + ": нужен ISO 8601, например 2026-10-29T10:00:00Z");
            }
        }
    }
}
