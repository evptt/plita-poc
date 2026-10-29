"""Analysis Engine: алгоритм вторичного анализа (раздел 4 ТЗ) и состояния Дома.

Модуль чистый: без БД, MQTT и сети. На вход получает отсчёты датчика и реле,
на выход отдаёт решения (смена состояния, тревога, уведомление). Всё общение
с внешним миром делает app/engine.py.

Этапы (рис. 6а/6б разбора ТЗ):
  1. Препроцессинг:   K_HT = Hum/Temp, K_VT = VOC/Temp, K_VH = VOC/Hum, R = радар.
  2. Производные:     Slope = (K[n+1] - K[n]) / dt, событие весом T = 2 с.
                      Если новая производная отличается от опорной больше чем на 5% -
                      отдельное событие; иначе усреднить, T суммируется, получается
                      новый опорный пакет -> стабилизированный коэффициент по каждому графику.
  3. Классификация:   сравнение с эталонами Варки / Жарки с допуском ±13%;
                      если класс неоднозначен - пересечение множеств HT ∩ VT ∩ VH.
  4. Вердикт:         матрица аварийных порогов для найденного процесса,
                      правило «И» с мягким допуском -> НОРМА или ТРЕВОГА.

ТРЕВОГА также выносится (раздел 4 ТЗ), если:
  * радар датчика сообщил о критической аномалии, или
  * опасная динамика: плита включена (просадка напряжения), температура/VOC растут,
    движения человека нет.

Состояния Дома (рис. 5): Idle -> Cooking -> Classified -> Normal / Alarm -> RelayOff -> Idle.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from enum import Enum

from .config import GRAPHS, AnalysisParams


class HomeState(str, Enum):
    IDLE = "Idle"
    COOKING = "Cooking"
    CLASSIFIED = "Classified"
    NORMAL = "Normal"
    ALARM = "Alarm"
    RELAY_OFF = "RelayOff"


class Verdict(str, Enum):
    NONE = "NONE"            # плита выключена, анализ не идёт
    UNDEFINED = "UNDEFINED"  # плита включена, процесс ещё не определён
    NORMAL = "NORMAL"        # НОРМА
    ALARM = "ALARM"          # ТРЕВОГА


class AlarmReason(str, Enum):
    RADAR_CRITICAL = "radar_critical"
    DANGEROUS_DYNAMICS = "dangerous_dynamics"
    PROCESS_THRESHOLDS = "process_thresholds"
    SENSOR_LOST = "sensor_lost"


ACTIVE_STATES = {HomeState.COOKING, HomeState.CLASSIFIED, HomeState.NORMAL}


# ---------------------------------------------------------------------------
# Входные отсчёты
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class SensorSample:
    t: float  # unix-время, с
    humidity: float
    temperature: float
    voc: float
    radar: int


@dataclass(frozen=True)
class RelaySample:
    t: float
    voltage: float
    current: float
    state: int


# ---------------------------------------------------------------------------
# Решения, которые анализатор отдаёт наружу
# ---------------------------------------------------------------------------

@dataclass
class StateChanged:
    state: HomeState
    verdict: Verdict
    process: str | None
    details: dict = field(default_factory=dict)


@dataclass
class Alarm:
    reason: AlarmReason
    details: dict
    cutoff: bool  # нужно ли отправить реле команду 0


@dataclass
class Notice:
    kind: str  # sensor_offline | sensor_online | relay_offline | relay_online
    details: dict = field(default_factory=dict)


Decision = StateChanged | Alarm | Notice


# ---------------------------------------------------------------------------
# Этап 1
# ---------------------------------------------------------------------------

_EPS = 1e-9


def ratios(s: SensorSample) -> dict[str, float] | None:
    """K_HT, K_VT, K_VH. None, если делить не на что (нулевая температура/влажность)."""
    if abs(s.temperature) < _EPS or abs(s.humidity) < _EPS:
        return None
    return {
        "HT": s.humidity / s.temperature,
        "VT": s.voc / s.temperature,
        "VH": s.voc / s.humidity,
    }


# ---------------------------------------------------------------------------
# Этап 2
# ---------------------------------------------------------------------------

@dataclass
class SlopeEvent:
    slope: float   # производная коэффициента, ед./мин
    weight_s: float  # T, суммарный вес
    t_end: float


class SlopeStabilizer:
    """Стабилизация производной одного графика (HT, VT или VH)."""

    def __init__(self, params: AnalysisParams, history: int = 50):
        self.p = params
        self.prev_k: float | None = None
        self.prev_t: float | None = None
        self.reference: SlopeEvent | None = None
        self.events: deque[SlopeEvent] = deque(maxlen=history)

    def reset(self) -> None:
        self.prev_k = self.prev_t = None
        self.reference = None
        self.events.clear()

    def push(self, t: float, k: float) -> SlopeEvent | None:
        if self.prev_t is None or self.prev_k is None:
            self.prev_t, self.prev_k = t, k
            return self.reference

        dt = t - self.prev_t
        if dt < self.p.min_dt_s:
            # дубль или слишком частый пакет - пропускаем для производной
            return self.reference
        if dt > self.p.max_dt_s:
            # большой разрыв в данных - производная по нему бессмысленна, начинаем заново
            self.reset()
            self.prev_t, self.prev_k = t, k
            return None

        slope = (k - self.prev_k) / dt * 60.0
        self.prev_t, self.prev_k = t, k
        weight = self.p.event_weight_s

        ref = self.reference
        if ref is None:
            self.reference = SlopeEvent(slope, weight, t)
            return self.reference

        denom = max(abs(ref.slope), self.p.slope_epsilon)
        if abs(slope - ref.slope) / denom > self.p.merge_threshold and \
                abs(slope - ref.slope) > self.p.slope_epsilon:
            # отдельное событие: старый опорный пакет уходит в историю
            self.events.append(ref)
            self.reference = SlopeEvent(slope, weight, t)
        else:
            # объединить: усреднить с весами, T суммируется -> новый опорный пакет
            total = ref.weight_s + weight
            self.reference = SlopeEvent((ref.slope * ref.weight_s + slope * weight) / total, total, t)
        return self.reference

    def stable_value(self) -> float | None:
        ref = self.reference
        if ref is None or ref.weight_s < self.p.min_stable_weight_s:
            return None
        return ref.slope


# ---------------------------------------------------------------------------
# Этап 3
# ---------------------------------------------------------------------------

def matching_processes(value: float, graph: str, params: AnalysisParams) -> set[str]:
    out = set()
    for process, ref in params.references.items():
        r = ref[graph]
        tol = max(params.classification_tolerance * abs(r), params.classification_abs_tolerance)
        if abs(value - r) <= tol:
            out.add(process)
    return out


def classify(coeffs: dict[str, float], params: AnalysisParams) -> tuple[str | None, dict]:
    """Возвращает (процесс или None, подробности для логов/приложения)."""
    sets = {g: matching_processes(coeffs[g], g, params) for g in GRAPHS}
    unique = {g: next(iter(s)) for g, s in sets.items() if len(s) == 1}
    details = {"candidates": {g: sorted(s) for g, s in sets.items()}}

    if len(unique) == len(GRAPHS) and len(set(unique.values())) == 1:
        details["method"] = "unambiguous"
        return next(iter(unique.values())), details

    inter = set.intersection(*sets.values())
    if len(inter) == 1:
        details["method"] = "intersection"
        return next(iter(inter)), details

    details["method"] = "undetermined"
    return None, details


# ---------------------------------------------------------------------------
# Этап 4
# ---------------------------------------------------------------------------

def check_thresholds(coeffs: dict[str, float], process: str,
                     params: AnalysisParams) -> tuple[bool, dict]:
    """Правило «И» с мягким допуском. Возвращает (в норме?, нарушения)."""
    violations = {}
    for g in GRAPHS:
        lo, hi = params.alarm_thresholds[process][g]
        margin = params.soft_tolerance * (hi - lo)
        if not (lo - margin <= coeffs[g] <= hi + margin):
            violations[g] = {"value": coeffs[g], "min": lo, "max": hi}
    return not violations, violations


# ---------------------------------------------------------------------------
# Опасная динамика
# ---------------------------------------------------------------------------

def linear_slope_per_min(points: list[tuple[float, float]]) -> float | None:
    """Наклон МНК, ед./мин."""
    n = len(points)
    if n < 2:
        return None
    mt = sum(t for t, _ in points) / n
    mv = sum(v for _, v in points) / n
    den = sum((t - mt) ** 2 for t, _ in points)
    if den <= 0:
        return None
    num = sum((t - mt) * (v - mv) for t, v in points)
    return num / den * 60.0


# ---------------------------------------------------------------------------
# Анализатор одного Дома
# ---------------------------------------------------------------------------

class HomeAnalyzer:
    def __init__(self, home_id: int, params: AnalysisParams, protection_enabled: bool = True):
        self.home_id = home_id
        self.p = params
        self.protection_enabled = protection_enabled

        self.state = HomeState.IDLE
        self.verdict = Verdict.NONE
        self.process: str | None = None
        self.last_reason: str | None = None

        self.stove_on = False
        self.stove_on_since: float | None = None
        self.last_relay: RelaySample | None = None
        self.last_sensor: SensorSample | None = None
        self.last_motion_t: float | None = None

        self.window: deque[SensorSample] = deque()
        self.stabilizers = {g: SlopeStabilizer(params) for g in GRAPHS}
        self.coeffs: dict[str, float | None] = {g: None for g in GRAPHS}

        self.last_alarm_t: float | None = None
        self.sensor_offline = False
        self.relay_offline = False

    # ---------------- вспомогательное ----------------

    def snapshot(self) -> dict:
        return {
            "state": self.state.value,
            "verdict": self.verdict.value,
            "process": self.process,
            "reason": self.last_reason,
            "stove_on": self.stove_on,
            "protection_enabled": self.protection_enabled,
            "coefficients": dict(self.coeffs),
            "sensor_offline": self.sensor_offline,
            "relay_offline": self.relay_offline,
        }

    def _set(self, out: list, state: HomeState, verdict: Verdict,
             process: str | None = None, **details) -> None:
        if (state, verdict, process) != (self.state, self.verdict, self.process):
            self.state, self.verdict, self.process = state, verdict, process
            out.append(StateChanged(state, verdict, process, details))

    def _reset_analysis(self) -> None:
        for s in self.stabilizers.values():
            s.reset()
        self.coeffs = {g: None for g in GRAPHS}
        self.window.clear()

    def _raise_alarm(self, out: list, t: float, reason: AlarmReason, details: dict) -> None:
        relay_on = self.last_relay is not None and self.last_relay.state == 1
        if self.state in (HomeState.ALARM, HomeState.RELAY_OFF):
            # тревога уже идёт: повторяем не чаще repeat_command_s и только если реле ещё включено
            if self.last_alarm_t is not None and t - self.last_alarm_t < self.p.repeat_command_s:
                return
            if self.state == HomeState.RELAY_OFF and not relay_on:
                return
            details = {**details, "repeat": True}

        self.last_alarm_t = t
        self.last_reason = reason.value
        self._set(out, HomeState.ALARM, Verdict.ALARM, self.process, reason=reason.value)
        cutoff = self.p.auto_cutoff and self.protection_enabled
        out.append(Alarm(reason, details, cutoff))

    # ---------------- внешние события ----------------

    def mark_relay_off(self) -> list[Decision]:
        """Команда 0 отправлена по тревоге -> RelayOff."""
        out: list[Decision] = []
        self._set(out, HomeState.RELAY_OFF, Verdict.ALARM, self.process, reason=self.last_reason)
        return out

    def manual_reset(self) -> list[Decision]:
        """Ручной сброс из приложения (команда 1) -> Idle."""
        out: list[Decision] = []
        self.last_alarm_t = None
        self.last_reason = None
        self._reset_analysis()
        self.stove_on_since = None
        self._set(out, HomeState.IDLE, Verdict.NONE, None, reason="manual_reset")
        return out

    def on_relay(self, s: RelaySample) -> list[Decision]:
        out: list[Decision] = []
        self.last_relay = s
        if self.relay_offline:
            self.relay_offline = False
            out.append(Notice("relay_online"))

        drop = self.p.nominal_voltage_v - s.voltage
        on = s.state == 1 and (drop >= self.p.voltage_drop_v or
                               (self.p.use_current and s.current >= self.p.current_on_a))

        if on and not self.stove_on:
            self.stove_on = True
            self.stove_on_since = s.t
            # окно «нет движения» отсчитывается не раньше момента включения плиты
            self.last_motion_t = max(self.last_motion_t or s.t, s.t)
            self._reset_analysis()
            if self.state == HomeState.IDLE:
                self._set(out, HomeState.COOKING, Verdict.UNDEFINED, None,
                          voltage=s.voltage, current=s.current)
        elif not on and self.stove_on:
            self.stove_on = False
            self.stove_on_since = None
            self._reset_analysis()
            if self.state in ACTIVE_STATES or \
                    (self.state == HomeState.ALARM and not self.protection_enabled):
                self._set(out, HomeState.IDLE, Verdict.NONE, None, reason="stove_off")

        if self.stove_on and self.state == HomeState.IDLE:
            # например, после ручного сброса при всё ещё включённой плите
            self._set(out, HomeState.COOKING, Verdict.UNDEFINED, None)
        return out

    def on_sensor(self, s: SensorSample) -> list[Decision]:
        out: list[Decision] = []
        self.last_sensor = s
        if self.sensor_offline:
            self.sensor_offline = False
            out.append(Notice("sensor_online"))

        if s.radar in self.p.radar_presence_values:
            self.last_motion_t = s.t

        self.window.append(s)
        while self.window and s.t - self.window[0].t > self.p.dynamics_window_s:
            self.window.popleft()

        # Этапы 1-2 считаем всегда, пока плита включена: так коэффициенты успевают стабилизироваться
        k = ratios(s)
        if k is not None and self.stove_on:
            for g in GRAPHS:
                self.stabilizers[g].push(s.t, k[g])
                self.coeffs[g] = self.stabilizers[g].stable_value()

        # 1) Радар: критическая аномалия
        if s.radar in self.p.radar_critical_values:
            self._raise_alarm(out, s.t, AlarmReason.RADAR_CRITICAL, {"radar": s.radar})
            return out

        if not self.stove_on:
            return out

        # 2) Опасная динамика: плита включена, температура/VOC растут, человека нет
        dyn = self._dangerous_dynamics(s.t)
        if dyn is not None:
            self._raise_alarm(out, s.t, AlarmReason.DANGEROUS_DYNAMICS, dyn)
            return out

        if self.state in (HomeState.ALARM, HomeState.RELAY_OFF):
            return out

        # 3) Классификация процесса
        if any(v is None for v in self.coeffs.values()):
            return out
        coeffs = {g: float(v) for g, v in self.coeffs.items()}  # type: ignore[arg-type]
        process, cls_details = classify(coeffs, self.p)
        if process is None:
            self._set(out, HomeState.COOKING, Verdict.UNDEFINED, None, **cls_details)
            return out

        # 4) Вердикт по матрице порогов процесса
        ok, violations = check_thresholds(coeffs, process, self.p)
        if ok:
            # Classified -> Normal (рис. 5); на новых данных снова проходим через классификацию
            if self.state not in (HomeState.CLASSIFIED, HomeState.NORMAL) or self.process != process:
                self._set(out, HomeState.CLASSIFIED, Verdict.UNDEFINED, process, **cls_details)
            self._set(out, HomeState.NORMAL, Verdict.NORMAL, process)
        else:
            self.process = process
            self._raise_alarm(out, s.t, AlarmReason.PROCESS_THRESHOLDS,
                              {"process": process, "coefficients": coeffs, "violations": violations})
        return out

    def _dangerous_dynamics(self, now: float) -> dict | None:
        if self.last_motion_t is None or now - self.last_motion_t < self.p.no_motion_s:
            return None
        pts = [x for x in self.window if self.stove_on_since is None or x.t >= self.stove_on_since]
        if len(pts) < self.p.dynamics_min_points:
            return None
        temp_slope = linear_slope_per_min([(x.t, x.temperature) for x in pts])
        voc_slope = linear_slope_per_min([(x.t, x.voc) for x in pts])
        if temp_slope is None or voc_slope is None:
            return None
        mean_voc = sum(x.voc for x in pts) / len(pts)
        voc_pct = voc_slope / abs(mean_voc) * 100.0 if abs(mean_voc) > _EPS else 0.0

        temp_rising = temp_slope >= self.p.temp_rise_c_per_min
        voc_rising = voc_pct >= self.p.voc_rise_pct_per_min
        if not (temp_rising or voc_rising):
            return None
        return {
            "temp_slope_c_per_min": round(temp_slope, 4),
            "voc_slope_pct_per_min": round(voc_pct, 4),
            "no_motion_s": round(now - self.last_motion_t, 1),
        }

    def on_tick(self, now: float) -> list[Decision]:
        """Сторож связи: вызывается раз в несколько секунд."""
        out: list[Decision] = []
        if self.stove_on and not self.sensor_offline:
            last = self.last_sensor.t if self.last_sensor else self.stove_on_since
            if last is not None and now - last > self.p.sensor_timeout_s:
                self.sensor_offline = True
                details = {"silent_s": round(now - last, 1)}
                out.append(Notice("sensor_offline", details))
                if self.p.cutoff_on_sensor_loss:
                    self._raise_alarm(out, now, AlarmReason.SENSOR_LOST, details)

        if self.last_relay is not None and not self.relay_offline and \
                now - self.last_relay.t > self.p.relay_timeout_s:
            self.relay_offline = True
            out.append(Notice("relay_offline", {"silent_s": round(now - self.last_relay.t, 1),
                                                "stove_was_on": self.stove_on}))
        return out
