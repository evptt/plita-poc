package com.plita.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plita.backend.analysis.AnalysisParams;
import com.plita.backend.analysis.AnalysisParams.AlarmCfg;
import com.plita.backend.analysis.AnalysisParams.AlarmThresholds;
import com.plita.backend.analysis.AnalysisParams.Classification;
import com.plita.backend.analysis.AnalysisParams.Derivatives;
import com.plita.backend.analysis.AnalysisParams.Dynamics;
import com.plita.backend.analysis.AnalysisParams.Radar;
import com.plita.backend.analysis.AnalysisParams.Range;
import com.plita.backend.analysis.AnalysisParams.StoveOn;
import com.plita.backend.analysis.AnalysisParams.Watchdog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Читает analysis-params.json (из classpath или из ANALYSIS_PARAMS_PATH). Ключи на snake_case. */
public final class AnalysisParamsLoader {

    public static final String CLASSPATH_RESOURCE = "/analysis-params.json";

    private AnalysisParamsLoader() {
    }

    public static AnalysisParams load(ObjectMapper mapper, String path) throws IOException {
        JsonNode root;
        if (path == null || path.isBlank()) {
            try (InputStream in = AnalysisParamsLoader.class.getResourceAsStream(CLASSPATH_RESOURCE)) {
                if (in == null) {
                    throw new IOException("нет ресурса " + CLASSPATH_RESOURCE);
                }
                root = mapper.readTree(in);
            }
        } else {
            root = mapper.readTree(Files.readString(Path.of(path)));
        }
        return fromJson(root);
    }

    public static AnalysisParams fromJson(JsonNode r) {
        JsonNode so = req(r, "stove_on");
        JsonNode rd = req(r, "radar");
        JsonNode dv = req(r, "derivatives");
        JsonNode cl = req(r, "classification");
        JsonNode at = req(r, "alarm_thresholds");
        JsonNode dy = req(r, "dynamics");
        JsonNode wd = req(r, "watchdog");
        JsonNode al = req(r, "alarm");

        Map<String, Map<String, Double>> refs = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = req(cl, "references").fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().startsWith("_")) {
                continue;
            }
            JsonNode n = e.getValue();
            refs.put(e.getKey(), AnalysisParams.graphs(num(n, "HT"), num(n, "VT"), num(n, "VH")));
        }

        Map<String, Map<String, Range>> matrix = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = req(at, "matrix").fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().startsWith("_")) {
                continue;
            }
            JsonNode n = e.getValue();
            matrix.put(e.getKey(), AnalysisParams.ranges(range(n, "HT"), range(n, "VT"), range(n, "VH")));
        }

        return new AnalysisParams(
                new StoveOn(num(so, "nominal_voltage_v"), num(so, "voltage_drop_v"),
                        so.path("use_voltage_drop").asBoolean(false), so.path("use_current").asBoolean(true),
                        num(so, "current_on_a")),
                new Radar(ints(rd, "presence_values"), ints(rd, "critical_values")),
                new Derivatives(num(dv, "event_weight_s"), num(dv, "merge_threshold"), num(dv, "slope_epsilon"),
                        num(dv, "min_dt_s"), num(dv, "max_dt_s"), num(dv, "min_stable_weight_s")),
                new Classification(num(cl, "tolerance"), num(cl, "abs_tolerance"), refs),
                new AlarmThresholds(num(at, "soft_tolerance"), matrix),
                new Dynamics(num(dy, "window_s"), (int) num(dy, "min_points"), num(dy, "temp_rise_c_per_min"),
                        num(dy, "voc_rise_pct_per_min"), num(dy, "no_motion_s")),
                new Watchdog(num(wd, "sensor_timeout_s"), num(wd, "relay_timeout_s"),
                        req(wd, "cutoff_on_sensor_loss").asBoolean()),
                new AlarmCfg(al.path("auto_cutoff").asBoolean(true), num(al, "repeat_command_s")));
    }

    private static JsonNode req(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("analysis-params.json: нет поля " + key);
        }
        return v;
    }

    private static double num(JsonNode n, String key) {
        JsonNode v = req(n, key);
        if (!v.isNumber()) {
            throw new IllegalArgumentException("analysis-params.json: " + key + " должно быть числом");
        }
        return v.asDouble();
    }

    private static Range range(JsonNode n, String key) {
        JsonNode v = req(n, key);
        if (!v.isArray() || v.size() != 2) {
            throw new IllegalArgumentException("analysis-params.json: " + key + " должно быть [min, max]");
        }
        return new Range(v.get(0).asDouble(), v.get(1).asDouble());
    }

    private static Set<Integer> ints(JsonNode n, String key) {
        Set<Integer> out = new LinkedHashSet<>();
        req(n, key).forEach(x -> out.add(x.asInt()));
        return Set.copyOf(out);
    }
}
