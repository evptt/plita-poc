package com.plita.backend.analysis;

import com.plita.backend.analysis.Decision.Alarm;
import com.plita.backend.analysis.Decision.Notice;
import com.plita.backend.analysis.Decision.StateChanged;
import com.plita.backend.analysis.Model.AlarmReason;
import com.plita.backend.analysis.Model.HomeState;
import com.plita.backend.analysis.Model.RelaySample;
import com.plita.backend.analysis.Model.SensorSample;
import com.plita.backend.analysis.Model.Verdict;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Analysis Engine для одного Дома: алгоритм раздела 4 ТЗ и состояния рис. 5.
 *
 * <p>ТРЕВОГА выносится, если:
 * <ul>
 *   <li>радар датчика сообщил о критической аномалии;</li>
 *   <li>опасная динамика: плита включена (просадка напряжения), температура/VOC растут, движения нет;</li>
 *   <li>процесс классифицирован, но коэффициенты вне матрицы аварийных порогов;</li>
 *   <li>(опционально) пропала связь с датчиком при включённой плите.</li>
 * </ul>
 * Idle -> Cooking -> Classified -> Normal / Alarm -> RelayOff -> Idle (только ручной сброс).
 *
 * <p>Класс не потокобезопасен - вызывающий (Engine) обеспечивает последовательный доступ.
 */
public final class HomeAnalyzer {

    private final long homeId;
    private final AnalysisParams p;
    private boolean protectionEnabled;

    private HomeState state = HomeState.IDLE;
    private Verdict verdict = Verdict.NONE;
    private String process;
    private String lastReason;

    private boolean stoveOn;
    private Double stoveOnSince;
    private RelaySample lastRelay;
    private SensorSample lastSensor;
    private Double lastMotionT;

    private final Deque<SensorSample> window = new ArrayDeque<>();
    private final Map<String, SlopeStabilizer> stabilizers = new LinkedHashMap<>();
    private final Map<String, Double> coeffs = new LinkedHashMap<>();

    private Double lastAlarmT;
    private boolean sensorOffline;
    private boolean relayOffline;

    public HomeAnalyzer(long homeId, AnalysisParams params, boolean protectionEnabled) {
        this.homeId = homeId;
        this.p = params;
        this.protectionEnabled = protectionEnabled;
        for (String g : AnalysisParams.GRAPHS) {
            stabilizers.put(g, new SlopeStabilizer(params.derivatives()));
            coeffs.put(g, null);
        }
    }

    // ------------------------------------------------------------------ доступ

    public long homeId() {
        return homeId;
    }

    public HomeState state() {
        return state;
    }

    public Verdict verdict() {
        return verdict;
    }

    public String process() {
        return process;
    }

    public String lastReason() {
        return lastReason;
    }

    public boolean stoveOn() {
        return stoveOn;
    }

    public boolean protectionEnabled() {
        return protectionEnabled;
    }

    public void setProtectionEnabled(boolean enabled) {
        this.protectionEnabled = enabled;
    }

    public SensorSample lastSensor() {
        return lastSensor;
    }

    public RelaySample lastRelay() {
        return lastRelay;
    }

    public boolean sensorOffline() {
        return sensorOffline;
    }

    public boolean relayOffline() {
        return relayOffline;
    }

    public Map<String, Double> coefficients() {
        return new LinkedHashMap<>(coeffs);
    }

    /** Восстановление тревоги после перезапуска сервиса (снять её можно только вручную). */
    public void restore(HomeState s, Verdict v, String proc, String reason) {
        this.state = s;
        this.verdict = v;
        this.process = proc;
        this.lastReason = reason;
    }

    // ------------------------------------------------------------------ вспомогательное

    private void set(List<Decision> out, HomeState s, Verdict v, String proc, Map<String, Object> details) {
        if (s != state || v != verdict || !Objects.equals(proc, process)) {
            state = s;
            verdict = v;
            process = proc;
            out.add(new StateChanged(s, v, proc, details == null ? Map.of() : details));
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private void resetAnalysis() {
        stabilizers.values().forEach(SlopeStabilizer::reset);
        AnalysisParams.GRAPHS.forEach(g -> coeffs.put(g, null));
        window.clear();
    }

    private void raiseAlarm(List<Decision> out, double t, AlarmReason reason, Map<String, Object> details) {
        boolean relayOn = lastRelay != null && lastRelay.state() == 1;
        Map<String, Object> d = new LinkedHashMap<>(details);
        if (state == HomeState.ALARM || state == HomeState.RELAY_OFF) {
            // тревога уже идёт: повторяем не чаще repeat_command_s и только если реле ещё включено
            if (lastAlarmT != null && t - lastAlarmT < p.alarm().repeatCommandS()) {
                return;
            }
            if (state == HomeState.RELAY_OFF && !relayOn) {
                return;
            }
            d.put("repeat", true);
        }
        lastAlarmT = t;
        lastReason = reason.value();
        set(out, HomeState.ALARM, Verdict.ALARM, process, map("reason", reason.value()));
        boolean cutoff = p.alarm().autoCutoff() && protectionEnabled;
        out.add(new Alarm(reason, d, cutoff));
    }

    // ------------------------------------------------------------------ внешние события

    /** Команда 0 отправлена по тревоге -> RelayOff. */
    public List<Decision> markRelayOff() {
        List<Decision> out = new ArrayList<>();
        set(out, HomeState.RELAY_OFF, Verdict.ALARM, process, map("reason", lastReason));
        return out;
    }

    /** Ручной сброс из приложения (команда 1) -> Idle. */
    public List<Decision> manualReset() {
        List<Decision> out = new ArrayList<>();
        lastAlarmT = null;
        lastReason = null;
        resetAnalysis();
        stoveOnSince = null;
        set(out, HomeState.IDLE, Verdict.NONE, null, map("reason", "manual_reset"));
        return out;
    }

    public List<Decision> onRelay(RelaySample s) {
        List<Decision> out = new ArrayList<>();
        lastRelay = s;
        if (relayOffline) {
            relayOffline = false;
            out.add(new Notice("relay_online", Map.of()));
        }

        var so = p.stoveOn();
        double drop = so.nominalVoltageV() - s.voltage();
        boolean on = s.state() == 1 && (drop >= so.voltageDropV() || (so.useCurrent() && s.current() >= so.currentOnA()));

        if (on && !stoveOn) {
            stoveOn = true;
            stoveOnSince = s.t();
            // окно «нет движения» отсчитывается не раньше момента включения плиты
            lastMotionT = Math.max(lastMotionT == null ? s.t() : lastMotionT, s.t());
            resetAnalysis();
            if (state == HomeState.IDLE) {
                set(out, HomeState.COOKING, Verdict.UNDEFINED, null,
                        map("voltage", s.voltage(), "current", s.current()));
            }
        } else if (!on && stoveOn) {
            stoveOn = false;
            stoveOnSince = null;
            resetAnalysis();
            if (state.isActive() || (state == HomeState.ALARM && !protectionEnabled)) {
                set(out, HomeState.IDLE, Verdict.NONE, null, map("reason", "stove_off"));
            }
        }

        if (stoveOn && state == HomeState.IDLE) {
            // например, после ручного сброса при всё ещё включённой плите
            set(out, HomeState.COOKING, Verdict.UNDEFINED, null, null);
        }
        return out;
    }

    public List<Decision> onSensor(SensorSample s) {
        List<Decision> out = new ArrayList<>();
        lastSensor = s;
        if (sensorOffline) {
            sensorOffline = false;
            out.add(new Notice("sensor_online", Map.of()));
        }

        if (p.radar().presenceValues().contains(s.radar())) {
            lastMotionT = s.t();
        }

        window.addLast(s);
        while (!window.isEmpty() && s.t() - window.peekFirst().t() > p.dynamics().windowS()) {
            window.removeFirst();
        }

        // Этапы 1-2 считаем, пока плита включена: так коэффициенты успевают стабилизироваться
        Map<String, Double> k = Algorithm.ratios(s);
        if (k != null && stoveOn) {
            for (String g : AnalysisParams.GRAPHS) {
                SlopeStabilizer st = stabilizers.get(g);
                st.push(s.t(), k.get(g));
                coeffs.put(g, st.stableValue());
            }
        }

        // 1) Радар: критическая аномалия
        if (p.radar().criticalValues().contains(s.radar())) {
            raiseAlarm(out, s.t(), AlarmReason.RADAR_CRITICAL, map("radar", s.radar()));
            return out;
        }

        if (!stoveOn) {
            return out;
        }

        // 2) Опасная динамика: плита включена, температура/VOC растут, человека нет
        Map<String, Object> dyn = dangerousDynamics(s.t());
        if (dyn != null) {
            raiseAlarm(out, s.t(), AlarmReason.DANGEROUS_DYNAMICS, dyn);
            return out;
        }

        if (state == HomeState.ALARM || state == HomeState.RELAY_OFF) {
            return out;
        }

        // 3) Классификация процесса
        if (coeffs.values().stream().anyMatch(Objects::isNull)) {
            return out;
        }
        Map<String, Double> c = new HashMap<>(coeffs);
        Algorithm.Classification cls = Algorithm.classify(c, p);
        if (cls.process() == null) {
            set(out, HomeState.COOKING, Verdict.UNDEFINED, null, cls.details());
            return out;
        }

        // 4) Вердикт по матрице порогов процесса
        Algorithm.ThresholdCheck check = Algorithm.checkThresholds(c, cls.process(), p);
        if (check.ok()) {
            // Classified -> Normal (рис. 5)
            if ((state != HomeState.CLASSIFIED && state != HomeState.NORMAL) || !cls.process().equals(process)) {
                set(out, HomeState.CLASSIFIED, Verdict.UNDEFINED, cls.process(), cls.details());
            }
            set(out, HomeState.NORMAL, Verdict.NORMAL, cls.process(), null);
        } else {
            process = cls.process();
            raiseAlarm(out, s.t(), AlarmReason.PROCESS_THRESHOLDS,
                    map("process", cls.process(), "coefficients", new LinkedHashMap<>(coeffs),
                            "violations", check.violations()));
        }
        return out;
    }

    private Map<String, Object> dangerousDynamics(double now) {
        var dy = p.dynamics();
        if (lastMotionT == null || now - lastMotionT < dy.noMotionS()) {
            return null;
        }
        List<SensorSample> pts = new ArrayList<>();
        for (SensorSample x : window) {
            if (stoveOnSince == null || x.t() >= stoveOnSince) {
                pts.add(x);
            }
        }
        if (pts.size() < dy.minPoints()) {
            return null;
        }
        Double tempSlope = Algorithm.linearSlopePerMin(pts.stream().map(x -> new double[]{x.t(), x.temperature()}).toList());
        Double vocSlope = Algorithm.linearSlopePerMin(pts.stream().map(x -> new double[]{x.t(), x.voc()}).toList());
        if (tempSlope == null || vocSlope == null) {
            return null;
        }
        double meanVoc = pts.stream().mapToDouble(SensorSample::voc).average().orElse(0);
        double vocPct = Math.abs(meanVoc) > Algorithm.EPS ? vocSlope / Math.abs(meanVoc) * 100.0 : 0.0;

        boolean tempRising = tempSlope >= dy.tempRiseCPerMin();
        boolean vocRising = vocPct >= dy.vocRisePctPerMin();
        if (!(tempRising || vocRising)) {
            return null;
        }
        return map("temp_slope_c_per_min", Algorithm.round4(tempSlope),
                "voc_slope_pct_per_min", Algorithm.round4(vocPct),
                "no_motion_s", Math.round((now - lastMotionT) * 10) / 10.0);
    }

    /** Сторож связи: вызывается раз в несколько секунд. */
    public List<Decision> onTick(double now) {
        List<Decision> out = new ArrayList<>();
        var wd = p.watchdog();
        if (stoveOn && !sensorOffline) {
            Double last = lastSensor != null ? Double.valueOf(lastSensor.t()) : stoveOnSince;
            if (last != null && now - last > wd.sensorTimeoutS()) {
                sensorOffline = true;
                Map<String, Object> details = map("silent_s", Math.round((now - last) * 10) / 10.0);
                out.add(new Notice("sensor_offline", details));
                if (wd.cutoffOnSensorLoss()) {
                    raiseAlarm(out, now, AlarmReason.SENSOR_LOST, details);
                }
            }
        }
        if (lastRelay != null && !relayOffline && now - lastRelay.t() > wd.relayTimeoutS()) {
            relayOffline = true;
            out.add(new Notice("relay_offline", map("silent_s", Math.round((now - lastRelay.t()) * 10) / 10.0,
                    "stove_was_on", stoveOn)));
        }
        return out;
    }
}
