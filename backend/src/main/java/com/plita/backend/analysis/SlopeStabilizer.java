package com.plita.backend.analysis;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Этап 2: стабилизация производной одного графика (HT, VT или VH).
 *
 * <p>Slope = (K[n+1] - K[n]) / dt (в ед./мин), событие весом T = 2 с. Если новая производная
 * отличается от опорной больше чем на 5% - отдельное событие; иначе усреднить с весами,
 * T суммируется (4 с) и получается новый опорный пакет. Стабильным коэффициент считается,
 * когда вес опорного пакета не меньше min_stable_weight_s (т.е. было хотя бы одно объединение).
 */
public final class SlopeStabilizer {

    public record SlopeEvent(double slope, double weightS, double tEnd) {
    }

    private static final int HISTORY = 50;

    private final AnalysisParams.Derivatives p;
    private Double prevK;
    private Double prevT;
    private SlopeEvent reference;
    private final Deque<SlopeEvent> events = new ArrayDeque<>();

    public SlopeStabilizer(AnalysisParams.Derivatives params) {
        this.p = params;
    }

    public void reset() {
        prevK = null;
        prevT = null;
        reference = null;
        events.clear();
    }

    public SlopeEvent push(double t, double k) {
        if (prevT == null || prevK == null) {
            prevT = t;
            prevK = k;
            return reference;
        }
        double dt = t - prevT;
        if (dt < p.minDtS()) {
            // дубль или слишком частый пакет - пропускаем для производной
            return reference;
        }
        if (dt > p.maxDtS()) {
            // большой разрыв в данных - производная по нему бессмысленна, начинаем заново
            reset();
            prevT = t;
            prevK = k;
            return null;
        }

        double slope = (k - prevK) / dt * 60.0;
        prevT = t;
        prevK = k;
        double weight = p.eventWeightS();

        if (reference == null) {
            reference = new SlopeEvent(slope, weight, t);
            return reference;
        }

        double denom = Math.max(Math.abs(reference.slope()), p.slopeEpsilon());
        double diff = Math.abs(slope - reference.slope());
        if (diff / denom > p.mergeThreshold() && diff > p.slopeEpsilon()) {
            // отдельное событие: старый опорный пакет уходит в историю
            events.addLast(reference);
            while (events.size() > HISTORY) {
                events.removeFirst();
            }
            reference = new SlopeEvent(slope, weight, t);
        } else {
            // объединить: усреднить с весами, T суммируется -> новый опорный пакет
            double total = reference.weightS() + weight;
            reference = new SlopeEvent((reference.slope() * reference.weightS() + slope * weight) / total, total, t);
        }
        return reference;
    }

    public Double stableValue() {
        if (reference == null || reference.weightS() < p.minStableWeightS()) {
            return null;
        }
        return reference.slope();
    }

    public SlopeEvent reference() {
        return reference;
    }

    public int eventCount() {
        return events.size();
    }
}
