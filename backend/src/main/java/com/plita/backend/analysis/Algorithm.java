package com.plita.backend.analysis;

import com.plita.backend.analysis.AnalysisParams.Range;
import com.plita.backend.analysis.Model.SensorSample;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Этапы алгоритма вторичного анализа (рис. 6а/6б разбора ТЗ), без состояния.
 *
 * <ol>
 *   <li>Препроцессинг: K_HT = Hum/Temp, K_VT = VOC/Temp, K_VH = VOC/Hum.</li>
 *   <li>Производные - см. {@link SlopeStabilizer}.</li>
 *   <li>Классификация: эталоны Варки/Жарки ±13%, при неоднозначности - пересечение HT ∩ VT ∩ VH.</li>
 *   <li>Вердикт: матрица аварийных порогов процесса, правило «И» с мягким допуском.</li>
 * </ol>
 */
public final class Algorithm {

    static final double EPS = 1e-9;

    private Algorithm() {
    }

    // ------------------------------------------------------------------ этап 1

    /** K_HT, K_VT, K_VH или null, если делить не на что. */
    public static Map<String, Double> ratios(SensorSample s) {
        if (Math.abs(s.temperature()) < EPS || Math.abs(s.humidity()) < EPS) {
            return null;
        }
        return AnalysisParams.graphs(
                s.humidity() / s.temperature(),
                s.voc() / s.temperature(),
                s.voc() / s.humidity());
    }

    // ------------------------------------------------------------------ этап 3

    public record Classification(String process, Map<String, Object> details) {
    }

    public static Set<String> matchingProcesses(double value, String graph, AnalysisParams p) {
        Set<String> out = new TreeSet<>();
        var c = p.classification();
        for (var e : c.references().entrySet()) {
            double r = e.getValue().get(graph);
            double tol = Math.max(c.tolerance() * Math.abs(r), c.absTolerance());
            if (Math.abs(value - r) <= tol) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public static Classification classify(Map<String, Double> coeffs, AnalysisParams p) {
        Map<String, Set<String>> sets = new LinkedHashMap<>();
        for (String g : AnalysisParams.GRAPHS) {
            sets.put(g, matchingProcesses(coeffs.get(g), g, p));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        sets.forEach((g, s) -> candidates.put(g, new ArrayList<>(s)));
        details.put("candidates", candidates);

        boolean allUnique = sets.values().stream().allMatch(s -> s.size() == 1);
        if (allUnique && new HashSet<>(sets.values()).size() == 1) {
            details.put("method", "unambiguous");
            return new Classification(sets.get("HT").iterator().next(), details);
        }

        Set<String> inter = new TreeSet<>(sets.get("HT"));
        inter.retainAll(sets.get("VT"));
        inter.retainAll(sets.get("VH"));
        if (inter.size() == 1) {
            details.put("method", "intersection");
            return new Classification(inter.iterator().next(), details);
        }
        details.put("method", "undetermined");
        return new Classification(null, details);
    }

    // ------------------------------------------------------------------ этап 4

    public record ThresholdCheck(boolean ok, Map<String, Object> violations) {
    }

    public static ThresholdCheck checkThresholds(Map<String, Double> coeffs, String process, AnalysisParams p) {
        Map<String, Object> violations = new LinkedHashMap<>();
        double soft = p.alarmThresholds().softTolerance();
        for (String g : AnalysisParams.GRAPHS) {
            Range r = p.alarmThresholds().matrix().get(process).get(g);
            double margin = soft * (r.max() - r.min());
            double v = coeffs.get(g);
            if (!(r.min() - margin <= v && v <= r.max() + margin)) {
                Map<String, Object> viol = new LinkedHashMap<>();
                viol.put("value", v);
                viol.put("min", r.min());
                viol.put("max", r.max());
                violations.put(g, viol);
            }
        }
        return new ThresholdCheck(violations.isEmpty(), violations);
    }

    // ------------------------------------------------------------------ опасная динамика

    /** Наклон МНК в ед./мин; points: пары [t, value]. */
    public static Double linearSlopePerMin(List<double[]> points) {
        int n = points.size();
        if (n < 2) {
            return null;
        }
        double mt = 0;
        double mv = 0;
        for (double[] pt : points) {
            mt += pt[0];
            mv += pt[1];
        }
        mt /= n;
        mv /= n;
        double num = 0;
        double den = 0;
        for (double[] pt : points) {
            num += (pt[0] - mt) * (pt[1] - mv);
            den += (pt[0] - mt) * (pt[0] - mt);
        }
        if (den <= 0) {
            return null;
        }
        return num / den * 60.0;
    }

    static double round4(double v) {
        return Math.round(v * 1e4) / 1e4;
    }
}
