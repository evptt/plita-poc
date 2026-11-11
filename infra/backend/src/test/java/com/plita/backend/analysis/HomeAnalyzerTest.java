package com.plita.backend.analysis;

import com.plita.backend.analysis.AnalysisParams.AlarmThresholds;
import com.plita.backend.analysis.AnalysisParams.Classification;
import com.plita.backend.analysis.AnalysisParams.Range;
import com.plita.backend.analysis.Decision.Alarm;
import com.plita.backend.analysis.Decision.Notice;
import com.plita.backend.analysis.Decision.StateChanged;
import com.plita.backend.analysis.Model.AlarmReason;
import com.plita.backend.analysis.Model.HomeState;
import com.plita.backend.analysis.Model.RelaySample;
import com.plita.backend.analysis.Model.SensorSample;
import com.plita.backend.analysis.Model.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeAnalyzerTest {

    private final AnalysisParams params = AnalysisParams.defaults();

    private static RelaySample relayOn(double t) {
        return new RelaySample(t, 218.9, 8.14, 1);
    }

    private static RelaySample relayOff(double t) {
        return new RelaySample(t, 230.0, 0.0, 0);
    }

    private static List<Alarm> alarms(List<Decision> out) {
        return out.stream().filter(d -> d instanceof Alarm).map(d -> (Alarm) d).toList();
    }

    // ---------------- этап 1 ----------------

    @Test
    void ratios() {
        Map<String, Double> k = Algorithm.ratios(new SensorSample(0, 50, 25, 100, 0));
        assertEquals(2.0, k.get("HT"), 1e-12);
        assertEquals(4.0, k.get("VT"), 1e-12);
        assertEquals(2.0, k.get("VH"), 1e-12);
        assertNull(Algorithm.ratios(new SensorSample(0, 50, 0, 100, 0)));
    }

    // ---------------- этап 2 ----------------

    @Test
    void stabilizerMergesCloseSlopes() {
        SlopeStabilizer st = new SlopeStabilizer(params.derivatives());
        st.push(0, 1.0);
        st.push(10, 1.1);                  // 0.6 / мин
        assertNull(st.stableValue());       // T = 2 с, ещё не стабилен
        st.push(20, 1.201);                 // 0.606 / мин, разница 1% -> объединение
        assertEquals(4.0, st.reference().weightS(), 1e-9);
        assertEquals((0.6 + 0.606) / 2, st.stableValue(), 1e-9);
    }

    @Test
    void stabilizerSeparatesDifferentSlopes() {
        SlopeStabilizer st = new SlopeStabilizer(params.derivatives());
        st.push(0, 1.0);
        st.push(10, 1.1);   // 0.6
        st.push(20, 1.3);   // 1.2 -> отличие 100% -> отдельное событие
        assertEquals(2.0, st.reference().weightS(), 1e-9);
        assertEquals(1, st.eventCount());
        assertNull(st.stableValue());
    }

    @Test
    void stabilizerResetsOnGap() {
        SlopeStabilizer st = new SlopeStabilizer(params.derivatives());
        st.push(0, 1.0);
        st.push(10, 1.1);
        st.push(10 + params.derivatives().maxDtS() + 1, 5.0);
        assertNull(st.reference());
    }

    // ---------------- этапы 3-4 ----------------

    @Test
    void classifyUnambiguous() {
        var c = Algorithm.classify(params.classification().references().get("boiling"), params);
        assertEquals("boiling", c.process());
        assertEquals("unambiguous", c.details().get("method"));
    }

    @Test
    void classifyUndetermined() {
        var c = Algorithm.classify(AnalysisParams.graphs(10, 10, 10), params);
        assertNull(c.process());
    }

    @Test
    void classifyIntersection() {
        // оба эталона одинаковы по HT -> на HT класс неоднозначен, но пересечение даёт frying
        Map<String, Map<String, Double>> refs = new LinkedHashMap<>(params.classification().references());
        refs.put("frying", AnalysisParams.graphs(0.020, 0.060, 0.030));
        AnalysisParams p = params.withClassification(new Classification(0.13, 0.0005, refs));
        var c = Algorithm.classify(AnalysisParams.graphs(0.020, 0.060, 0.030), p);
        assertEquals("frying", c.process());
        assertEquals("intersection", c.details().get("method"));
    }

    @Test
    void thresholdsAndRule() {
        assertTrue(Algorithm.checkThresholds(AnalysisParams.graphs(0, 0, 0), "boiling", params).ok());
        var check = Algorithm.checkThresholds(AnalysisParams.graphs(0, 0.5, 0), "boiling", params);
        assertFalse(check.ok());
        assertEquals(List.of("VT"), new ArrayList<>(check.violations().keySet()));
    }

    @Test
    void thresholdsSoftTolerance() {
        Range r = params.alarmThresholds().matrix().get("boiling").get("HT");
        double justOut = r.max() + 0.5 * params.alarmThresholds().softTolerance() * (r.max() - r.min());
        assertTrue(Algorithm.checkThresholds(AnalysisParams.graphs(justOut, 0, 0), "boiling", params).ok());
    }

    @Test
    void invalidParamsRejected() {
        Map<String, Map<String, Range>> matrix = new LinkedHashMap<>(params.alarmThresholds().matrix());
        matrix.remove("frying");
        assertThrows(IllegalArgumentException.class,
                () -> params.withAlarmThresholds(new AlarmThresholds(0.05, matrix)));
    }

    // ---------------- анализатор Дома ----------------

    @Test
    void stoveOnByCurrent() {
        HomeAnalyzer a = new HomeAnalyzer(1, params, true);
        List<Decision> out = a.onRelay(relayOn(0));
        assertTrue(a.stoveOn());
        assertEquals(HomeState.COOKING, a.state());
        assertTrue(out.get(0) instanceof StateChanged);
        a.onRelay(relayOff(1));
        assertFalse(a.stoveOn());
        assertEquals(HomeState.IDLE, a.state());
    }

    @Test
    void relayOnWithoutLoadIsNotCooking() {
        HomeAnalyzer a = new HomeAnalyzer(1, params, true);
        a.onRelay(new RelaySample(0, 229.5, 0.0, 1));
        assertFalse(a.stoveOn());
        assertEquals(HomeState.IDLE, a.state());
    }

    /** Сеть 220 В без нагрузки: при выключенной просадке напряжения плита не считается включённой. */
    @Test
    void lowGridVoltageWithoutCurrentIsNotCooking() {
        HomeAnalyzer a = new HomeAnalyzer(1, params, true);
        a.onRelay(new RelaySample(0, 220.0, 0.0, 1));
        assertFalse(a.stoveOn());
        assertEquals(HomeState.IDLE, a.state());
    }

    @Test
    void voltageDropCanBeEnabled() {
        var so = params.stoveOn();
        AnalysisParams p = new AnalysisParams(
                new AnalysisParams.StoveOn(so.nominalVoltageV(), so.voltageDropV(), true, so.useCurrent(), so.currentOnA()),
                params.radar(), params.derivatives(), params.classification(), params.alarmThresholds(),
                params.dynamics(), params.watchdog(), params.alarm());
        HomeAnalyzer a = new HomeAnalyzer(1, p, true);
        a.onRelay(new RelaySample(0, 220.0, 0.0, 1));
        assertTrue(a.stoveOn());
    }

    @Test
    void radarCriticalAlarm() {
        HomeAnalyzer a = new HomeAnalyzer(1, params, true);
        a.onRelay(relayOn(0));
        List<Alarm> al = alarms(a.onSensor(new SensorSample(1, 50, 25, 100, 2)));
        assertEquals(1, al.size());
        assertEquals(AlarmReason.RADAR_CRITICAL, al.get(0).reason());
        assertTrue(al.get(0).cutoff());
        assertEquals(HomeState.ALARM, a.state());
        a.markRelayOff();
        assertEquals(HomeState.RELAY_OFF, a.state());
        a.manualReset();
        assertEquals(HomeState.IDLE, a.state());
    }

    @Test
    void protectionDisabledNoCutoff() {
        HomeAnalyzer a = new HomeAnalyzer(1, params, false);
        List<Alarm> al = alarms(a.onSensor(new SensorSample(1, 50, 25, 100, 2)));
        assertFalse(al.get(0).cutoff());
    }

    private static List<Decision> feed(HomeAnalyzer a, double t0, double t1, double step,
                                       DoubleUnaryOperator temp, DoubleUnaryOperator voc, int radar) {
        List<Decision> out = new ArrayList<>();
        for (double t = t0; t <= t1; t += step) {
            a.onRelay(relayOn(t));
            out.addAll(a.onSensor(new SensorSample(t, 50.0, temp.applyAsDouble(t), voc.applyAsDouble(t), radar)));
        }
        return out;
    }

    @Test
    void dangerousDynamics() {
        HomeAnalyzer a = new HomeAnalyzer(1, params, true);
        // температура растёт на 3 °C/мин, движения нет дольше no_motion_s
        double noMotion = params.dynamics().noMotionS();
        List<Alarm> al = alarms(feed(a, 0, noMotion + 60, 10, t -> 25 + t / 20, t -> 100.0, 0));
        assertFalse(al.isEmpty());
        assertEquals(AlarmReason.DANGEROUS_DYNAMICS, al.get(0).reason());
        assertTrue(((Number) al.get(0).details().get("no_motion_s")).doubleValue() >= noMotion);
    }

    @Test
    void noAlarmWhenPersonPresent() {
        HomeAnalyzer a = new HomeAnalyzer(1, params, true);
        double noMotion = params.dynamics().noMotionS();
        assertTrue(alarms(feed(a, 0, noMotion + 60, 10, t -> 25 + t / 20, t -> 100.0, 1)).isEmpty());
    }

    @Test
    void alarmNotSpammed() {
        HomeAnalyzer a = new HomeAnalyzer(1, params, true);
        a.onRelay(relayOn(0));
        assertFalse(alarms(a.onSensor(new SensorSample(1, 50, 25, 100, 2))).isEmpty());
        a.markRelayOff();
        assertTrue(alarms(a.onSensor(new SensorSample(2, 50, 25, 100, 2))).isEmpty());
        // реле так и не выключилось - через repeat_command_s команда повторяется
        double later = params.alarm().repeatCommandS() + 5;
        a.onRelay(relayOn(later));
        List<Alarm> rep = alarms(a.onSensor(new SensorSample(later, 50, 25, 100, 2)));
        assertFalse(rep.isEmpty());
        assertEquals(Boolean.TRUE, rep.get(0).details().get("repeat"));
    }

    /**
     * Эталон Варки, согласованный по трём графикам: VH = VOC/Hum = K_VT / K_HT, поэтому
     * d(VT/HT) = (VT'*HT - VT*HT') / HT^2 = (0.004*2 - 4*0.02) / 4 = -0.018 в минуту.
     */
    private AnalysisParams consistentParams(Range boilingHt) {
        Map<String, Map<String, Double>> refs = new LinkedHashMap<>(params.classification().references());
        refs.put("boiling", AnalysisParams.graphs(0.020, 0.004, -0.018));
        Map<String, Map<String, Range>> matrix = new LinkedHashMap<>(params.alarmThresholds().matrix());
        matrix.put("boiling", AnalysisParams.ranges(boilingHt, new Range(-0.020, 0.030), new Range(-0.030, 0.010)));
        return params.withClassification(new Classification(0.13, 0.0005, refs))
                .withAlarmThresholds(new AlarmThresholds(0.05, matrix));
    }

    /** Ряд, у которого производные коэффициентов совпадают с эталоном процесса. */
    private static List<Decision> processSeries(HomeAnalyzer a, Map<String, Double> ref) {
        double temp = 25.0;
        List<Decision> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double t = i * 10;
            double minutes = t / 60;
            double hum = (2.0 + ref.get("HT") * minutes) * temp;
            double voc = (4.0 + ref.get("VT") * minutes) * temp;
            a.onRelay(relayOn(t));
            out.addAll(a.onSensor(new SensorSample(t, hum, temp, voc, 1)));
        }
        return out;
    }

    @Test
    void classifiedNormalBoiling() {
        AnalysisParams p = consistentParams(new Range(-0.050, 0.100));
        HomeAnalyzer a = new HomeAnalyzer(1, p, true);
        List<Decision> out = processSeries(a, p.classification().references().get("boiling"));
        assertTrue(out.stream().anyMatch(d -> d instanceof StateChanged sc && sc.state() == HomeState.CLASSIFIED));
        assertEquals(HomeState.NORMAL, a.state());
        assertEquals(Verdict.NORMAL, a.verdict());
        assertEquals("boiling", a.process());
    }

    @Test
    void classifiedButOutOfThresholds() {
        AnalysisParams p = consistentParams(new Range(-0.01, 0.0));  // эталон HT 0.02 вне диапазона
        HomeAnalyzer a = new HomeAnalyzer(1, p, true);
        List<Alarm> al = alarms(processSeries(a, p.classification().references().get("boiling")));
        assertFalse(al.isEmpty());
        assertEquals(AlarmReason.PROCESS_THRESHOLDS, al.get(0).reason());
        assertTrue(((Map<?, ?>) al.get(0).details().get("violations")).containsKey("HT"));
    }

    @Test
    void watchdog() {
        HomeAnalyzer a = new HomeAnalyzer(1, params, true);
        a.onRelay(relayOn(0));
        a.onSensor(new SensorSample(0, 50, 25, 100, 1));
        List<String> kinds = a.onTick(params.watchdog().sensorTimeoutS() + 1).stream()
                .filter(d -> d instanceof Notice).map(d -> ((Notice) d).kind()).toList();
        assertTrue(kinds.contains("sensor_offline"));
        assertTrue(kinds.contains("relay_offline"));
        List<Decision> back = a.onSensor(new SensorSample(params.watchdog().sensorTimeoutS() + 2, 50, 25, 100, 1));
        assertTrue(back.stream().anyMatch(d -> d instanceof Notice n && n.kind().equals("sensor_online")));
    }

    /** Автомат Tuya опрашивается раз в 2 минуты: молчание 61 с - не обрыв связи. */
    @Test
    void watchdogUsesRelayTimeoutOfPlatform() {
        HomeAnalyzer a = new HomeAnalyzer(1, params, true);
        a.onRelay(relayOn(0));
        a.onSensor(new SensorSample(0, 50, 25, 100, 1));
        double tuyaTimeout = 3 * 120 + 30;
        List<String> kinds = a.onTick(61, tuyaTimeout).stream()
                .filter(d -> d instanceof Notice).map(d -> ((Notice) d).kind()).toList();
        assertFalse(kinds.contains("relay_offline"));
        kinds = a.onTick(tuyaTimeout + 1, tuyaTimeout).stream()
                .filter(d -> d instanceof Notice).map(d -> ((Notice) d).kind()).toList();
        assertTrue(kinds.contains("relay_offline"));
    }
}
