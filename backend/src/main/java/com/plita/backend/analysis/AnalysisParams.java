package com.plita.backend.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Параметры алгоритма вторичного анализа (раздел 4 ТЗ).
 * Загружаются из analysis-params.json (см. {@code AnalysisParamsLoader}); всё, что помечено
 * в файле как PLACEHOLDER, в ТЗ не задано и должно прийти от заказчика.
 * Коэффициенты: HT = Hum/Temp, VT = VOC/Temp, VH = VOC/Hum. Производные - в ед./мин.
 */
public record AnalysisParams(
        StoveOn stoveOn,
        Radar radar,
        Derivatives derivatives,
        Classification classification,
        AlarmThresholds alarmThresholds,
        Dynamics dynamics,
        Watchdog watchdog,
        AlarmCfg alarm) {

    public static final List<String> GRAPHS = List.of("HT", "VT", "VH");

    /** Плита включена = реле On И просадка напряжения (ток - дополнительный признак). */
    public record StoveOn(double nominalVoltageV, double voltageDropV, boolean useCurrent, double currentOnA) {}

    public record Radar(Set<Integer> presenceValues, Set<Integer> criticalValues) {}

    public record Derivatives(double eventWeightS, double mergeThreshold, double slopeEpsilon,
                              double minDtS, double maxDtS, double minStableWeightS) {}

    /** references: процесс -> график -> идеальный коэффициент. */
    public record Classification(double tolerance, double absTolerance,
                                 Map<String, Map<String, Double>> references) {}

    public record Range(double min, double max) {}

    /** matrix: процесс -> график -> [min, max]. */
    public record AlarmThresholds(double softTolerance, Map<String, Map<String, Range>> matrix) {}

    public record Dynamics(double windowS, int minPoints, double tempRiseCPerMin,
                           double vocRisePctPerMin, double noMotionS) {}

    public record Watchdog(double sensorTimeoutS, double relayTimeoutS, boolean cutoffOnSensorLoss) {}

    public record AlarmCfg(boolean autoCutoff, double repeatCommandS) {}

    public AnalysisParams {
        for (var e : classification.references().entrySet()) {
            for (String g : GRAPHS) {
                if (!e.getValue().containsKey(g)) {
                    throw new IllegalArgumentException("classification.references." + e.getKey() + ": нет " + g);
                }
            }
            var thr = alarmThresholds.matrix().get(e.getKey());
            if (thr == null) {
                throw new IllegalArgumentException("Для процесса " + e.getKey() + " нет аварийных порогов");
            }
            for (String g : GRAPHS) {
                Range r = thr.get(g);
                if (r == null) {
                    throw new IllegalArgumentException("alarm_thresholds." + e.getKey() + ": нет " + g);
                }
                if (r.min() > r.max()) {
                    throw new IllegalArgumentException("alarm_thresholds." + e.getKey() + "." + g + ": min > max");
                }
            }
        }
    }

    /** Значения по умолчанию - те же, что в src/main/resources/analysis-params.json. */
    public static AnalysisParams defaults() {
        Map<String, Map<String, Double>> refs = new LinkedHashMap<>();
        refs.put("boiling", graphs(0.020, 0.004, 0.001));
        refs.put("frying", graphs(-0.005, 0.060, 0.030));

        Map<String, Map<String, Range>> matrix = new LinkedHashMap<>();
        matrix.put("boiling", ranges(new Range(-0.050, 0.100), new Range(-0.020, 0.030), new Range(-0.010, 0.010)));
        matrix.put("frying", ranges(new Range(-0.050, 0.050), new Range(-0.020, 0.150), new Range(-0.010, 0.080)));

        return new AnalysisParams(
                new StoveOn(230.0, 5.0, true, 0.5),
                new Radar(Set.of(1), Set.of(2)),
                new Derivatives(2.0, 0.05, 1e-6, 0.5, 120.0, 4.0),
                new Classification(0.13, 0.0005, refs),
                new AlarmThresholds(0.05, matrix),
                new Dynamics(120.0, 3, 1.0, 10.0, 300.0),
                new Watchdog(60.0, 10.0, false),
                new AlarmCfg(true, 30.0));
    }

    public static Map<String, Double> graphs(double ht, double vt, double vh) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("HT", ht);
        m.put("VT", vt);
        m.put("VH", vh);
        return Collections.unmodifiableMap(m);
    }

    public static Map<String, Range> ranges(Range ht, Range vt, Range vh) {
        Map<String, Range> m = new LinkedHashMap<>();
        m.put("HT", ht);
        m.put("VT", vt);
        m.put("VH", vh);
        return Collections.unmodifiableMap(m);
    }

    // ----- удобные копии с изменениями (для тестов и тонкой настройки) -----

    public AnalysisParams withClassification(Classification c) {
        return new AnalysisParams(stoveOn, radar, derivatives, c, alarmThresholds, dynamics, watchdog, alarm);
    }

    public AnalysisParams withAlarmThresholds(AlarmThresholds t) {
        return new AnalysisParams(stoveOn, radar, derivatives, classification, t, dynamics, watchdog, alarm);
    }

    public AnalysisParams withWatchdog(Watchdog w) {
        return new AnalysisParams(stoveOn, radar, derivatives, classification, alarmThresholds, dynamics, w, alarm);
    }
}
