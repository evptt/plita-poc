"""Разбор MQTT-топиков и пакетов (раздел 3 ТЗ).

device/{device_id}/telemetry - 13 байт: float32 влажность, float32 температура,
                               float32 VOC, uint8 радар/вердикт датчика.
relay/{relay_id}/power       - формат в ТЗ не описан; эмулятор шлёт JSON
                               {"voltage": 218.9, "current": 8.14, "state": 1}.
relay/{relay_id}/command     - строка "0" (выключить плиту) или "1" (включить).
"""

from __future__ import annotations

import json
import math
import struct
from dataclasses import dataclass

SENSOR_PACKET_SIZE = 13


class PacketError(ValueError):
    """Пакет не удалось разобрать."""


@dataclass(frozen=True)
class SensorReading:
    humidity: float
    temperature: float
    voc: float
    radar: int


@dataclass(frozen=True)
class RelayReading:
    voltage: float
    current: float
    state: int  # 1 = On, 0 = Off


@dataclass(frozen=True)
class Topic:
    kind: str  # "sensor" | "relay"
    device_id: str


def parse_topic(topic: str) -> Topic | None:
    parts = topic.split("/")
    if len(parts) != 3 or not parts[1]:
        return None
    if parts[0] == "device" and parts[2] == "telemetry":
        return Topic("sensor", parts[1])
    if parts[0] == "relay" and parts[2] == "power":
        return Topic("relay", parts[1])
    return None


def _check_finite(**values: float) -> None:
    bad = [k for k, v in values.items() if not math.isfinite(v)]
    if bad:
        raise PacketError(f"нечисловые значения в полях: {', '.join(bad)}")


def parse_sensor_packet(payload: bytes, byte_order: str = "little") -> SensorReading:
    if len(payload) != SENSOR_PACKET_SIZE:
        raise PacketError(f"пакет датчика должен быть {SENSOR_PACKET_SIZE} байт, пришло {len(payload)}")
    fmt = ("<" if byte_order == "little" else ">") + "fffB"
    humidity, temperature, voc, radar = struct.unpack(fmt, payload)
    _check_finite(humidity=humidity, temperature=temperature, voc=voc)
    return SensorReading(humidity=humidity, temperature=temperature, voc=voc, radar=radar)


def build_sensor_packet(humidity: float, temperature: float, voc: float, radar: int,
                        byte_order: str = "little") -> bytes:
    """Обратная операция - для тестов и эмулятора."""
    fmt = ("<" if byte_order == "little" else ">") + "fffB"
    return struct.pack(fmt, humidity, temperature, voc, radar)


_VOLTAGE_KEYS = ("voltage", "u", "v")
_CURRENT_KEYS = ("current", "i", "a")
_STATE_KEYS = ("state", "status", "relay_state", "on")


def _pick(obj: dict, keys: tuple[str, ...], field: str):
    lowered = {str(k).lower(): v for k, v in obj.items()}
    for k in keys:
        if k in lowered:
            return lowered[k]
    raise PacketError(f"в пакете реле нет поля {field}")


def _parse_state(value) -> int:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, (int, float)):
        if value in (0, 1):
            return int(value)
        raise PacketError(f"статус реле должен быть 0 или 1, пришло {value}")
    text = str(value).strip().lower()
    if text in {"1", "on", "true"}:
        return 1
    if text in {"0", "off", "false"}:
        return 0
    raise PacketError(f"непонятный статус реле: {value!r}")


def parse_relay_packet(payload: bytes) -> RelayReading:
    """Формат пакета реле в ТЗ не задан (вопрос 3), поэтому разбор вынесен сюда.

    Поддерживается JSON эмулятора {"voltage", "current", "state"} (а также синонимы
    U / I / status, state "on"/"off") и запасной текстовый вариант "218.9,8.14,1".
    Когда заказчик зафиксирует формат, менять нужно только эту функцию.
    """
    try:
        text = payload.decode("utf-8").strip()
    except UnicodeDecodeError as e:
        raise PacketError("пакет реле не является текстом/JSON") from e
    if not text:
        raise PacketError("пустой пакет реле")

    if text.startswith("{"):
        try:
            obj = json.loads(text)
        except json.JSONDecodeError as e:
            raise PacketError(f"битый JSON реле: {e}") from e
        if not isinstance(obj, dict):
            raise PacketError("JSON реле должен быть объектом")
        voltage = _pick(obj, _VOLTAGE_KEYS, "voltage")
        current = _pick(obj, _CURRENT_KEYS, "current")
        state = _pick(obj, _STATE_KEYS, "state")
    else:
        parts = [p.strip() for p in text.replace(";", ",").split(",")]
        if len(parts) != 3:
            raise PacketError("текстовый пакет реле должен быть 'voltage,current,state'")
        voltage, current, state = parts

    try:
        voltage_f = float(voltage)
        current_f = float(current)
    except (TypeError, ValueError) as e:
        raise PacketError("напряжение и ток должны быть числами") from e
    _check_finite(voltage=voltage_f, current=current_f)
    return RelayReading(voltage=voltage_f, current=current_f, state=_parse_state(state))
