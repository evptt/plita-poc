import json
from pathlib import Path

import pytest

from app.analysis import (Alarm, AlarmReason, HomeAnalyzer, HomeState, Notice, RelaySample,
                          SensorSample, SlopeStabilizer, StateChanged, Verdict, check_thresholds,
                          classify, ratios)
from app.config import DEFAULT_ANALYSIS_PARAMS, AnalysisParams


def load_raw():
    return json.loads(Path(DEFAULT_ANALYSIS_PARAMS).read_text(encoding="utf-8"))


@pytest.fixture
def params():
    return AnalysisParams.load(DEFAULT_ANALYSIS_PARAMS)


def relay_on(t, v=218.9, i=8.14):
    return RelaySample(t, v, i, 1)


def relay_off(t):
    return RelaySample(t, 230.0, 0.0, 0)


# ---------------- этап 1 ----------------

def test_ratios():
    k = ratios(SensorSample(0, humidity=50, temperature=25, voc=100, radar=0))
    assert k == {"HT": 2.0, "VT": 4.0, "VH": 2.0}
    assert ratios(SensorSample(0, 50, 0, 100, 0)) is None


# ---------------- этап 2 ----------------

def test_stabilizer_merges_close_slopes(params):
    st = SlopeStabilizer(params)
    st.push(0, 1.0)
    st.push(10, 1.1)  # 0.6 / мин
    assert st.stable_value() is None  # T = 2 с, ещё не стабилен
    st.push(20, 1.201)  # 0.606 / мин, разница 1% -> объединение
    assert st.reference.weight_s == pytest.approx(4.0)
    assert st.stable_value() == pytest.approx((0.6 + 0.606) / 2)


def test_stabilizer_separates_different_slopes(params):
    st = SlopeStabilizer(params)
    st.push(0, 1.0)
    st.push(10, 1.1)   # 0.6
    st.push(20, 1.3)   # 1.2 -> отличие 100% -> отдельное событие
    assert st.reference.weight_s == pytest.approx(2.0)
    assert len(st.events) == 1
    assert st.stable_value() is None


def test_stabilizer_resets_on_gap(params):
    st = SlopeStabilizer(params)
    st.push(0, 1.0)
    st.push(10, 1.1)
    st.push(10 + params.max_dt_s + 1, 5.0)
    assert st.reference is None


# ---------------- этапы 3-4 ----------------

def test_classify_unambiguous(params):
    process, d = classify(dict(params.references["boiling"]), params)
    assert process == "boiling" and d["method"] == "unambiguous"


def test_classify_undetermined(params):
    process, _ = classify({"HT": 10.0, "VT": 10.0, "VH": 10.0}, params)
    assert process is None


def test_classify_intersection():
    raw = load_raw()
    # оба эталона одинаковы по HT -> на HT класс неоднозначен, но пересечение даёт frying
    raw["classification"]["references"]["frying"]["HT"] = 0.020
    p = AnalysisParams.from_dict(raw)
    process, d = classify({"HT": 0.020, "VT": 0.060, "VH": 0.030}, p)
    assert process == "frying" and d["method"] == "intersection"


def test_thresholds_and_rule(params):
    ok, v = check_thresholds({"HT": 0.0, "VT": 0.0, "VH": 0.0}, "boiling", params)
    assert ok and not v
    ok, v = check_thresholds({"HT": 0.0, "VT": 0.5, "VH": 0.0}, "boiling", params)
    assert not ok and set(v) == {"VT"}


def test_thresholds_soft_tolerance(params):
    lo, hi = params.alarm_thresholds["boiling"]["HT"]
    just_out = hi + 0.5 * params.soft_tolerance * (hi - lo)
    ok, _ = check_thresholds({"HT": just_out, "VT": 0.0, "VH": 0.0}, "boiling", params)
    assert ok


# ---------------- анализатор Дома ----------------

def test_stove_on_by_voltage_drop(params):
    a = HomeAnalyzer(1, params)
    out = a.on_relay(relay_on(0))
    assert a.stove_on and a.state == HomeState.COOKING
    assert isinstance(out[0], StateChanged)
    a.on_relay(relay_off(1))
    assert not a.stove_on and a.state == HomeState.IDLE


def test_relay_on_without_load_is_not_cooking(params):
    a = HomeAnalyzer(1, params)
    a.on_relay(RelaySample(0, 229.5, 0.0, 1))
    assert not a.stove_on and a.state == HomeState.IDLE


def test_radar_critical_alarm(params):
    a = HomeAnalyzer(1, params)
    a.on_relay(relay_on(0))
    out = a.on_sensor(SensorSample(1, 50, 25, 100, radar=2))
    alarms = [d for d in out if isinstance(d, Alarm)]
    assert alarms and alarms[0].reason == AlarmReason.RADAR_CRITICAL and alarms[0].cutoff
    assert a.state == HomeState.ALARM
    a.mark_relay_off()
    assert a.state == HomeState.RELAY_OFF
    a.manual_reset()
    assert a.state == HomeState.IDLE


def test_protection_disabled_no_cutoff(params):
    a = HomeAnalyzer(1, params, protection_enabled=False)
    out = a.on_sensor(SensorSample(1, 50, 25, 100, radar=2))
    assert [d for d in out if isinstance(d, Alarm)][0].cutoff is False


def _feed(a, t0, t1, step, temp_fn, voc_fn, hum=50.0, radar=0):
    out = []
    t = t0
    while t <= t1:
        a.on_relay(relay_on(t))
        out += a.on_sensor(SensorSample(t, hum, temp_fn(t), voc_fn(t), radar))
        t += step
    return out


def test_dangerous_dynamics(params):
    a = HomeAnalyzer(1, params)
    # температура растёт на 3 °C/мин, движения нет дольше no_motion_s
    out = _feed(a, 0, params.no_motion_s + 60, 10, lambda t: 25 + t / 20, lambda t: 100.0)
    alarms = [d for d in out if isinstance(d, Alarm)]
    assert alarms and alarms[0].reason == AlarmReason.DANGEROUS_DYNAMICS
    assert alarms[0].details["no_motion_s"] >= params.no_motion_s


def test_no_alarm_when_person_present(params):
    a = HomeAnalyzer(1, params)
    out = _feed(a, 0, params.no_motion_s + 60, 10, lambda t: 25 + t / 20, lambda t: 100.0, radar=1)
    assert not [d for d in out if isinstance(d, Alarm)]


def test_alarm_not_spammed(params):
    a = HomeAnalyzer(1, params)
    a.on_relay(relay_on(0))
    first = a.on_sensor(SensorSample(1, 50, 25, 100, 2))
    a.mark_relay_off()
    again = a.on_sensor(SensorSample(2, 50, 25, 100, 2))
    assert [d for d in first if isinstance(d, Alarm)]
    assert not [d for d in again if isinstance(d, Alarm)]
    # реле так и не выключилось - через repeat_command_s команда повторяется
    a.on_relay(relay_on(params.repeat_command_s + 5))
    later = a.on_sensor(SensorSample(params.repeat_command_s + 5, 50, 25, 100, 2))
    rep = [d for d in later if isinstance(d, Alarm)]
    assert rep and rep[0].details.get("repeat")


def _process_series(a, ref, start=0, n=8, step=10):
    """Подбираем ряд так, чтобы производные коэффициентов совпали с эталоном процесса."""
    temp = 25.0
    out = []
    for i in range(n):
        t = start + i * step
        minutes = i * step / 60
        k_ht = 2.0 + ref["HT"] * minutes
        k_vt = 4.0 + ref["VT"] * minutes
        hum = k_ht * temp
        voc = k_vt * temp
        a.on_relay(relay_on(t))
        out += a.on_sensor(SensorSample(t, hum, temp, voc, 1))
    return out


def consistent_raw():
    """Эталон Варки, согласованный по трём графикам.

    VH = VOC/Hum = K_VT / K_HT, поэтому его производная задаётся двумя другими:
    d(VT/HT) = (VT'*HT - VT*HT') / HT^2 = (0.004*2 - 4*0.02) / 4 = -0.018 в минуту.
    """
    raw = load_raw()
    raw["classification"]["references"]["boiling"] = {"HT": 0.020, "VT": 0.004, "VH": -0.018}
    raw["alarm_thresholds"]["matrix"]["boiling"]["VH"] = [-0.030, 0.010]
    return raw


def test_classified_normal_boiling():
    p = AnalysisParams.from_dict(consistent_raw())
    a = HomeAnalyzer(1, p)
    out = _process_series(a, p.references["boiling"])
    states = [d.state for d in out if isinstance(d, StateChanged)]
    assert HomeState.CLASSIFIED in states
    assert a.state == HomeState.NORMAL and a.verdict == Verdict.NORMAL and a.process == "boiling"


def test_classified_but_out_of_thresholds():
    raw = consistent_raw()
    raw["alarm_thresholds"]["matrix"]["boiling"]["HT"] = [-0.01, 0.0]  # эталон 0.02 вне диапазона
    p = AnalysisParams.from_dict(raw)
    a = HomeAnalyzer(1, p)
    out = _process_series(a, p.references["boiling"])
    alarms = [d for d in out if isinstance(d, Alarm)]
    assert alarms and alarms[0].reason == AlarmReason.PROCESS_THRESHOLDS
    assert "HT" in alarms[0].details["violations"]


def test_watchdog(params):
    a = HomeAnalyzer(1, params)
    a.on_relay(relay_on(0))
    a.on_sensor(SensorSample(0, 50, 25, 100, 1))
    out = a.on_tick(params.sensor_timeout_s + 1)
    kinds = [d.kind for d in out if isinstance(d, Notice)]
    assert "sensor_offline" in kinds and "relay_offline" in kinds
    out = a.on_sensor(SensorSample(params.sensor_timeout_s + 2, 50, 25, 100, 1))
    assert any(isinstance(d, Notice) and d.kind == "sensor_online" for d in out)
