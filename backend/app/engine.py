"""Связка модулей бэкенда (рис. 1 разбора ТЗ):

MQTT Ingest      - разбор пакетов, запись в telemetry_history, передача в анализ;
Analysis Engine  - HomeAnalyzer на каждый Дом (app/analysis.py);
Command Service  - публикация 0/1 в relay/{id}/command по тревоге или по команде пользователя;
уведомления      - события в приложение через WebSocket.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass
from datetime import datetime, timezone

import asyncpg

from .analysis import (Alarm, Decision, HomeAnalyzer, HomeState, Notice, RelaySample,
                       SensorSample, StateChanged, Verdict)
from .config import AnalysisParams, Settings
from .mqtt import MqttManager, MqttUnavailable
from .parsers import PacketError, parse_relay_packet, parse_sensor_packet, parse_topic
from .ws import WsHub

log = logging.getLogger(__name__)

UNKNOWN_DEVICE_RECHECK_S = 30.0
WATCHDOG_PERIOD_S = 5.0


@dataclass
class DeviceInfo:
    type: str
    home_id: int | None


@dataclass
class HomeInfo:
    user_id: int
    name: str
    sensor_id: str | None = None
    relay_id: str | None = None


def utcnow_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class Engine:
    def __init__(self, pool: asyncpg.Pool, settings: Settings, params: AnalysisParams, hub: WsHub):
        self.pool = pool
        self.s = settings
        self.p = params
        self.hub = hub
        self.mqtt: MqttManager | None = None

        self.devices: dict[str, DeviceInfo] = {}
        self.homes: dict[int, HomeInfo] = {}
        self.analyzers: dict[int, HomeAnalyzer] = {}
        self._unknown_until: dict[str, float] = {}
        self.stats = {"sensor_packets": 0, "relay_packets": 0, "bad_packets": 0,
                      "unknown_device_packets": 0, "alarms": 0, "commands": 0}

    # ------------------------------------------------------------------ загрузка

    async def load(self) -> None:
        homes = await self.pool.fetch("SELECT id, user_id, name FROM homes")
        self.homes = {r["id"]: HomeInfo(r["user_id"], r["name"]) for r in homes}
        devices = await self.pool.fetch("SELECT id, type::text AS type, home_id FROM devices")
        self.devices = {r["id"]: DeviceInfo(r["type"], r["home_id"]) for r in devices}
        for dev_id, d in self.devices.items():
            self._attach_device(dev_id, d)

        states = {r["home_id"]: r for r in await self.pool.fetch("SELECT * FROM home_state")}
        for home_id in self.homes:
            self._create_analyzer(home_id, states.get(home_id))
        log.info("Загружено: Домов %d, устройств %d", len(self.homes), len(self.devices))

    def _attach_device(self, dev_id: str, d: DeviceInfo) -> None:
        home = self.homes.get(d.home_id) if d.home_id is not None else None
        if home is None:
            return
        if d.type == "sensor":
            home.sensor_id = dev_id
        elif d.type == "relay":
            home.relay_id = dev_id

    def _create_analyzer(self, home_id: int, row=None) -> HomeAnalyzer:
        protection = True if row is None else bool(row["protection_enabled"])
        a = HomeAnalyzer(home_id, self.p, protection_enabled=protection)
        if row is not None and row["state"] in (HomeState.ALARM.value, HomeState.RELAY_OFF.value):
            # тревога переживает перезапуск сервиса: снять её можно только вручную из приложения
            a.state = HomeState(row["state"])
            a.verdict = Verdict(row["verdict"]) if row["verdict"] else Verdict.ALARM
            a.process = row["process"]
            a.last_reason = row["reason"]
        self.analyzers[home_id] = a
        return a

    async def reload_home(self, home_id: int) -> None:
        """Вызывается после создания/удаления Дома."""
        row = await self.pool.fetchrow("SELECT id, user_id, name FROM homes WHERE id = $1", home_id)
        for dev_id in [k for k, v in self.devices.items() if v.home_id == home_id]:
            self.devices[dev_id].home_id = None
        if row is None:
            self.homes.pop(home_id, None)
            self.analyzers.pop(home_id, None)
            return
        self.homes[home_id] = HomeInfo(row["user_id"], row["name"])
        for r in await self.pool.fetch(
                "SELECT id, type::text AS type, home_id FROM devices WHERE home_id = $1", home_id):
            self.devices[r["id"]] = DeviceInfo(r["type"], r["home_id"])
            self._unknown_until.pop(r["id"], None)
            self._attach_device(r["id"], self.devices[r["id"]])
        if home_id not in self.analyzers:
            st = await self.pool.fetchrow("SELECT * FROM home_state WHERE home_id = $1", home_id)
            self._create_analyzer(home_id, st)

    async def _lookup_device(self, dev_id: str) -> DeviceInfo | None:
        d = self.devices.get(dev_id)
        if d is not None:
            return d
        now = time.monotonic()
        if self._unknown_until.get(dev_id, 0) > now:
            return None
        row = await self.pool.fetchrow(
            "SELECT id, type::text AS type, home_id FROM devices WHERE id = $1", dev_id)
        if row is None:
            self._unknown_until[dev_id] = now + UNKNOWN_DEVICE_RECHECK_S
            log.warning("Пакет от незаведённого устройства %s пропущен (заведите его в devices "
                        "или создайте Дом через POST /api/v1/homes)", dev_id)
            return None
        d = DeviceInfo(row["type"], row["home_id"])
        self.devices[dev_id] = d
        self._attach_device(dev_id, d)
        return d

    # ------------------------------------------------------------------ MQTT Ingest

    async def handle_message(self, topic: str, payload: bytes) -> None:
        parsed = parse_topic(topic)
        if parsed is None:
            return
        device = await self._lookup_device(parsed.device_id)
        if device is None:
            self.stats["unknown_device_packets"] += 1
            return
        if device.type != parsed.kind:
            self.stats["bad_packets"] += 1
            log.warning("Устройство %s заведено как %s, но пишет в топик %s", parsed.device_id,
                        device.type, topic)
            return

        t = time.time()
        try:
            if parsed.kind == "sensor":
                r = parse_sensor_packet(payload, self.s.sensor_byte_order)
            else:
                rr = parse_relay_packet(payload)
        except PacketError as e:
            self.stats["bad_packets"] += 1
            log.warning("Битый пакет в %s: %s", topic, e)
            return

        if parsed.kind == "sensor":
            self.stats["sensor_packets"] += 1
            # Таблица из разбора ТЗ: val_1 = Temp, val_2 = Hum, val_3 = VOC, val_4 = Radar
            await self.pool.execute(
                'INSERT INTO telemetry_history (device_id, "timestamp", val_1, val_2, val_3, val_4) '
                "VALUES ($1, now(), $2, $3, $4, $5)",
                parsed.device_id, r.temperature, r.humidity, r.voc, float(r.radar))
            sample = SensorSample(t, r.humidity, r.temperature, r.voc, r.radar)
            if device.home_id in self.analyzers:
                await self._apply(device.home_id, self.analyzers[device.home_id].on_sensor(sample))
        else:
            self.stats["relay_packets"] += 1
            # val_1 = напряжение, val_2 = ток, val_3 = NULL, val_4 = статус реле
            await self.pool.execute(
                'INSERT INTO telemetry_history (device_id, "timestamp", val_1, val_2, val_3, val_4) '
                "VALUES ($1, now(), $2, $3, NULL, $4)",
                parsed.device_id, rr.voltage, rr.current, float(rr.state))
            sample_r = RelaySample(t, rr.voltage, rr.current, rr.state)
            if device.home_id in self.analyzers:
                await self._apply(device.home_id, self.analyzers[device.home_id].on_relay(sample_r))

    # ------------------------------------------------------------------ решения анализатора

    async def _apply(self, home_id: int, decisions: list[Decision]) -> None:
        for d in decisions:
            if isinstance(d, StateChanged):
                await self._save_state(home_id)
                log.info("Дом %d: состояние %s, вердикт %s, процесс %s", home_id, d.state.value,
                         d.verdict.value, d.process)
                await self._notify(home_id, {"type": "state", **self.status(home_id)})
            elif isinstance(d, Alarm):
                await self._handle_alarm(home_id, d)
            elif isinstance(d, Notice):
                log.warning("Дом %d: %s %s", home_id, d.kind, d.details)
                await self._notify(home_id, {"type": d.kind, "home_id": home_id,
                                             "details": d.details, "at": utcnow_iso()})

    async def _handle_alarm(self, home_id: int, alarm: Alarm) -> None:
        self.stats["alarms"] += 1
        log.error("Дом %d: ТРЕВОГА (%s) %s", home_id, alarm.reason.value, alarm.details)
        command_sent = False
        if alarm.cutoff:
            command_sent = await self.send_command(home_id, 0, source="auto",
                                                   reason=alarm.reason.value)
        await self.pool.execute(
            "INSERT INTO alarms (home_id, reason, details, command_sent) VALUES ($1, $2, $3::jsonb, $4)",
            home_id, alarm.reason.value, json.dumps(alarm.details, default=str), command_sent)
        # «красный экран»
        await self._notify(home_id, {
            "type": "alarm", "home_id": home_id, "reason": alarm.reason.value,
            "details": alarm.details, "command_sent": command_sent, "at": utcnow_iso()})
        if command_sent:
            await self._apply(home_id, self.analyzers[home_id].mark_relay_off())
        else:
            await self._save_state(home_id)

    async def _save_state(self, home_id: int) -> None:
        a = self.analyzers[home_id]
        await self.pool.execute("""
            INSERT INTO home_state (home_id, state, verdict, process, reason, protection_enabled, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, now())
            ON CONFLICT (home_id) DO UPDATE SET state = EXCLUDED.state, verdict = EXCLUDED.verdict,
                process = EXCLUDED.process, reason = EXCLUDED.reason, updated_at = now()""",
            home_id, a.state.value, a.verdict.value, a.process, a.last_reason, a.protection_enabled)

    async def _notify(self, home_id: int, event: dict) -> None:
        home = self.homes.get(home_id)
        if home is not None:
            await self.hub.send_to_user(home.user_id, event)

    # ------------------------------------------------------------------ Command Service

    async def send_command(self, home_id: int, command: int, source: str,
                           user_id: int | None = None, reason: str | None = None) -> bool:
        home = self.homes.get(home_id)
        if home is None or home.relay_id is None:
            log.error("Дом %s: нет реле, команду %d отправить некуда", home_id, command)
            return False
        if self.mqtt is None:
            return False
        try:
            await self.mqtt.send_relay_command(home.relay_id, command)
        except (MqttUnavailable, Exception) as e:  # noqa: BLE001 - любая ошибка = команда не ушла
            log.error("Дом %d: команда %d в relay/%s/command не отправлена: %s",
                      home_id, command, home.relay_id, e)
            return False
        self.stats["commands"] += 1
        log.info("Дом %d: команда %d -> relay/%s/command (%s)", home_id, command, home.relay_id, source)
        await self.pool.execute(
            "INSERT INTO relay_commands (relay_id, home_id, command, source, user_id, reason) "
            "VALUES ($1, $2, $3, $4, $5, $6)",
            home.relay_id, home_id, command, source, user_id, reason)
        return True

    async def manual_command(self, home_id: int, command: int, user_id: int) -> bool:
        ok = await self.send_command(home_id, command, source="manual", user_id=user_id)
        if ok and command == 1:
            # ручной сброс из приложения: RelayOff/Alarm -> Idle (рис. 5)
            await self._apply(home_id, self.analyzers[home_id].manual_reset())
            await self._save_state(home_id)
        return ok

    async def set_protection(self, home_id: int, enabled: bool) -> None:
        a = self.analyzers[home_id]
        a.protection_enabled = enabled
        await self.pool.execute("""
            INSERT INTO home_state (home_id, state, verdict, protection_enabled)
            VALUES ($1, $2, $3, $4)
            ON CONFLICT (home_id) DO UPDATE SET protection_enabled = EXCLUDED.protection_enabled,
                updated_at = now()""", home_id, a.state.value, a.verdict.value, enabled)
        await self._notify(home_id, {"type": "state", **self.status(home_id)})

    # ------------------------------------------------------------------ сторож связи

    async def watchdog_loop(self) -> None:
        while True:
            await asyncio.sleep(WATCHDOG_PERIOD_S)
            now = time.time()
            for home_id, a in list(self.analyzers.items()):
                try:
                    await self._apply(home_id, a.on_tick(now))
                except Exception:
                    log.exception("Сторож: ошибка для Дома %d", home_id)

    # ------------------------------------------------------------------ для API

    def status(self, home_id: int) -> dict:
        a = self.analyzers.get(home_id)
        home = self.homes.get(home_id)
        snap = a.snapshot() if a else {}
        last_sensor = a.last_sensor if a else None
        last_relay = a.last_relay if a else None

        def ts(t: float | None):
            if t is None:
                return None
            return datetime.fromtimestamp(t, timezone.utc).isoformat().replace("+00:00", "Z")

        return {
            "home_id": home_id,
            "sensor_id": home.sensor_id if home else None,
            "relay_id": home.relay_id if home else None,
            **snap,
            "last_sensor": None if last_sensor is None else {
                "at": ts(last_sensor.t), "temperature": last_sensor.temperature,
                "humidity": last_sensor.humidity, "voc": last_sensor.voc, "radar": last_sensor.radar},
            "last_relay": None if last_relay is None else {
                "at": ts(last_relay.t), "voltage": last_relay.voltage,
                "current": last_relay.current, "state": last_relay.state},
        }
