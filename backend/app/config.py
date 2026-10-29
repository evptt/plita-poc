"""Настройки сервиса.

Инфраструктурные переменные приходят из docker-compose (см. отчёт по инфраструктуре):
DATABASE_URL, MQTT_HOST, MQTT_PORT, MQTT_USER, MQTT_PASSWORD, JWT_SECRET, GOOGLE_CLIENT_ID.
Остальные переменные необязательны и имеют безопасные значения по умолчанию.

Параметры алгоритма анализа (пороги, эталоны) лежат отдельно в analysis_params.json,
потому что их должен дать заказчик (вопросы 2, 4, 7 из раздела 8 разбора ТЗ).
"""

from __future__ import annotations

import json
import logging
import os
import secrets
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger(__name__)

DEFAULT_ANALYSIS_PARAMS = Path(__file__).with_name("analysis_params.json")


def _env(name: str, default: str = "") -> str:
    value = os.environ.get(name)
    return value.strip() if value is not None and value.strip() != "" else default


def _bool(name: str, default: bool) -> bool:
    raw = _env(name, "")
    if raw == "":
        return default
    return raw.lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int) -> int:
    raw = _env(name, "")
    return int(raw) if raw else default


@dataclass(frozen=True)
class Settings:
    database_url: str
    mqtt_host: str
    mqtt_port: int
    mqtt_user: str
    mqtt_password: str
    mqtt_client_id: str
    jwt_secret: str
    jwt_ttl_hours: int
    google_client_ids: tuple[str, ...]
    dev_auth_enabled: bool
    seed_devices: tuple[tuple[str, str], ...]
    sensor_byte_order: str  # "little" | "big"
    firmware_latest_version: str
    analysis_params_path: str
    log_level: str

    @staticmethod
    def from_env() -> "Settings":
        jwt_secret = _env("JWT_SECRET")
        if not jwt_secret:
            jwt_secret = secrets.token_urlsafe(48)
            log.warning("JWT_SECRET не задан: сгенерирован временный, токены сбросятся при перезапуске")

        seed: list[tuple[str, str]] = []
        for item in _env("SEED_DEVICES", "emu-sensor-1:sensor,emu-relay-1:relay").split(","):
            item = item.strip()
            if not item:
                continue
            dev_id, _, dev_type = item.partition(":")
            if dev_type not in {"sensor", "relay"}:
                raise ValueError(f"SEED_DEVICES: неверный тип в '{item}', нужно sensor или relay")
            seed.append((dev_id.strip(), dev_type))

        byte_order = _env("SENSOR_BYTE_ORDER", "little").lower()
        if byte_order not in {"little", "big"}:
            raise ValueError("SENSOR_BYTE_ORDER должен быть little или big")

        hostname = os.environ.get("HOSTNAME", "local")
        return Settings(
            database_url=_env("DATABASE_URL", "postgresql://plita:plita@localhost:5432/plita"),
            mqtt_host=_env("MQTT_HOST", "localhost"),
            mqtt_port=_int("MQTT_PORT", 1883),
            mqtt_user=_env("MQTT_USER"),
            mqtt_password=_env("MQTT_PASSWORD"),
            mqtt_client_id=_env("MQTT_CLIENT_ID", f"plita-backend-{hostname}"),
            jwt_secret=jwt_secret,
            jwt_ttl_hours=_int("JWT_TTL_HOURS", 24 * 30),
            google_client_ids=tuple(x.strip() for x in _env("GOOGLE_CLIENT_ID").split(",") if x.strip()),
            dev_auth_enabled=_bool("DEV_AUTH_ENABLED", False),
            seed_devices=tuple(seed),
            sensor_byte_order=byte_order,
            firmware_latest_version=_env("FIRMWARE_LATEST_VERSION", "0.0.0"),
            analysis_params_path=_env("ANALYSIS_PARAMS_PATH", str(DEFAULT_ANALYSIS_PARAMS)),
            log_level=_env("LOG_LEVEL", "INFO").upper(),
        )


# ---------------------------------------------------------------------------
# Параметры алгоритма анализа
# ---------------------------------------------------------------------------

GRAPHS = ("HT", "VT", "VH")


@dataclass(frozen=True)
class AnalysisParams:
    # Когда плита считается включённой (данные реле 1 Гц)
    nominal_voltage_v: float
    voltage_drop_v: float
    use_current: bool
    current_on_a: float
    # Радар
    radar_presence_values: frozenset[int]
    radar_critical_values: frozenset[int]
    # Этап 2: производные
    event_weight_s: float
    merge_threshold: float
    slope_epsilon: float
    min_dt_s: float
    max_dt_s: float
    min_stable_weight_s: float
    # Этап 3: классификация
    classification_tolerance: float
    classification_abs_tolerance: float
    references: dict[str, dict[str, float]]
    # Этап 4: матрица аварийных порогов
    soft_tolerance: float
    alarm_thresholds: dict[str, dict[str, tuple[float, float]]]
    # Опасная динамика
    dynamics_window_s: float
    dynamics_min_points: int
    temp_rise_c_per_min: float
    voc_rise_pct_per_min: float
    no_motion_s: float
    # Обрыв связи
    sensor_timeout_s: float
    relay_timeout_s: float
    cutoff_on_sensor_loss: bool
    # Тревога
    repeat_command_s: float
    auto_cutoff: bool

    @staticmethod
    def load(path: str | os.PathLike) -> "AnalysisParams":
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        return AnalysisParams.from_dict(raw)

    @staticmethod
    def from_dict(raw: dict) -> "AnalysisParams":
        so = raw["stove_on"]
        rd = raw["radar"]
        dv = raw["derivatives"]
        cl = raw["classification"]
        at = raw["alarm_thresholds"]
        dy = raw["dynamics"]
        wd = raw["watchdog"]
        al = raw["alarm"]

        references: dict[str, dict[str, float]] = {}
        for process, coeffs in cl["references"].items():
            if process.startswith("_"):
                continue
            references[process] = {g: float(coeffs[g]) for g in GRAPHS}

        thresholds: dict[str, dict[str, tuple[float, float]]] = {}
        for process, coeffs in at["matrix"].items():
            if process.startswith("_"):
                continue
            thresholds[process] = {}
            for g in GRAPHS:
                lo, hi = (float(v) for v in coeffs[g])
                if lo > hi:
                    raise ValueError(f"alarm_thresholds.{process}.{g}: min > max")
                thresholds[process][g] = (lo, hi)

        missing = set(references) - set(thresholds)
        if missing:
            raise ValueError(f"Для процессов {sorted(missing)} нет аварийных порогов")

        return AnalysisParams(
            nominal_voltage_v=float(so["nominal_voltage_v"]),
            voltage_drop_v=float(so["voltage_drop_v"]),
            use_current=bool(so.get("use_current", True)),
            current_on_a=float(so["current_on_a"]),
            radar_presence_values=frozenset(int(v) for v in rd["presence_values"]),
            radar_critical_values=frozenset(int(v) for v in rd["critical_values"]),
            event_weight_s=float(dv["event_weight_s"]),
            merge_threshold=float(dv["merge_threshold"]),
            slope_epsilon=float(dv["slope_epsilon"]),
            min_dt_s=float(dv["min_dt_s"]),
            max_dt_s=float(dv["max_dt_s"]),
            min_stable_weight_s=float(dv["min_stable_weight_s"]),
            classification_tolerance=float(cl["tolerance"]),
            classification_abs_tolerance=float(cl["abs_tolerance"]),
            references=references,
            soft_tolerance=float(at["soft_tolerance"]),
            alarm_thresholds=thresholds,
            dynamics_window_s=float(dy["window_s"]),
            dynamics_min_points=int(dy["min_points"]),
            temp_rise_c_per_min=float(dy["temp_rise_c_per_min"]),
            voc_rise_pct_per_min=float(dy["voc_rise_pct_per_min"]),
            no_motion_s=float(dy["no_motion_s"]),
            sensor_timeout_s=float(wd["sensor_timeout_s"]),
            relay_timeout_s=float(wd["relay_timeout_s"]),
            cutoff_on_sensor_loss=bool(wd["cutoff_on_sensor_loss"]),
            repeat_command_s=float(al["repeat_command_s"]),
            auto_cutoff=bool(al.get("auto_cutoff", True)),
        )
