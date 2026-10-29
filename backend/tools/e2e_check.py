"""Сквозная проверка бэкенда на живых PostgreSQL и MQTT-брокере.

Запуск (бэкенд должен работать с DEV_AUTH_ENABLED=true):
    python tools/e2e_check.py --api https://localhost --mqtt-host localhost --mqtt-port 1883 \
        [--mqtt-user backend --mqtt-password ...]

Сценарий: вход -> WebSocket -> создание Дома с emu-sensor-1/emu-relay-1 -> пакеты реле и датчика ->
аналитика -> радар «критическая аномалия» -> команда 0 в реле + тревога в WebSocket ->
ручное включение (сброс) -> проверки ошибок и доступа -> OTA-заглушки.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import ssl
import struct
import sys
import time
import uuid

import aiomqtt
import httpx
import websockets

OK = "\033[32mOK\033[0m"
FAIL = "\033[31mFAIL\033[0m"
failures = 0


def check(cond: bool, what: str, extra: str = "") -> None:
    global failures
    print(f"  [{OK if cond else FAIL}] {what}" + (f"  ({extra})" if extra and not cond else ""))
    if not cond:
        failures += 1


def sensor_packet(hum, temp, voc, radar):
    return struct.pack("<fffB", hum, temp, voc, radar)


async def main(a) -> int:
    suffix = uuid.uuid4().hex[:6]
    sensor_id = a.sensor_id or "emu-sensor-1"
    relay_id = a.relay_id or "emu-relay-1"
    mqtt_kw = dict(hostname=a.mqtt_host, port=a.mqtt_port,
                   username=a.mqtt_user, password=a.mqtt_password)

    async with httpx.AsyncClient(base_url=a.api, timeout=10, verify=False) as http, \
            aiomqtt.Client(identifier=f"e2e-{suffix}", **mqtt_kw) as mq:

        print("1. Сервис")
        r = await http.get("/health")
        check(r.status_code == 200 and r.json()["status"] == "ok", "GET /health -> 200 ok")
        check(r.json().get("mqtt") is True, "бэкенд подключён к MQTT")

        print("2. Авторизация")
        r = await http.post("/api/v1/auth/dev", json={"email": f"e2e-{suffix}@test.local", "name": "E2E"})
        check(r.status_code == 200, "POST /api/v1/auth/dev -> JWT", r.text)
        token = r.json()["access_token"]
        H = {"Authorization": f"Bearer {token}"}
        r = await http.get("/api/v1/auth/me", headers=H)
        check(r.status_code == 200 and r.json()["name"] == "E2E", "GET /api/v1/auth/me")
        r = await http.get("/api/v1/homes")
        check(r.status_code == 401, "без JWT -> 401")
        r = await http.post("/api/v1/auth/google", json={"id_token": "x" * 20})
        check(r.status_code in (401, 503), "POST /auth/google с мусорным токеном -> 401/503")

        print("3. Сопряжение")
        r = await http.post("/api/v1/homes", headers=H,
                            json={"name": "Кухня E2E", "sensor_id": sensor_id, "relay_id": relay_id})
        if r.status_code == 409:
            print("   устройства заняты прошлым прогоном - освобождаю")
            for h in (await http.get("/api/v1/homes", headers=H)).json():
                await http.delete(f"/api/v1/homes/{h['home_id']}", headers=H)
        check(r.status_code in (201, 409), "POST /api/v1/homes -> 201", r.text)
        if r.status_code != 201:
            print("   ! устройства привязаны к другому пользователю; передайте --sensor-id/--relay-id")
            return 1
        home = r.json()
        home_id = home["home_id"]
        check(home["sensor_id"] == sensor_id and home["relay_id"] == relay_id, "Дом содержит датчик и реле")

        r2 = await http.post("/api/v1/auth/dev", json={"email": f"other-{suffix}@test.local"})
        H2 = {"Authorization": f"Bearer {r2.json()['access_token']}"}
        r = await http.post("/api/v1/homes", headers=H2,
                            json={"name": "Чужой", "sensor_id": sensor_id, "relay_id": f"x-{suffix}"})
        check(r.status_code == 409, "чужой пользователь не может забрать датчик -> 409")
        r = await http.get(f"/api/v1/homes/{home_id}", headers=H2)
        check(r.status_code == 404, "чужой Дом не виден -> 404")
        r = await http.get("/api/v1/homes", headers=H)
        check(any(h["home_id"] == home_id for h in r.json()), "GET /api/v1/homes")

        print("4. WebSocket и поток данных")
        ws_url = a.api.replace("http", "ws", 1) + f"/api/v1/ws?token={token}"
        ssl_ctx = None
        if ws_url.startswith("wss://"):
            ssl_ctx = ssl.create_default_context()
            ssl_ctx.check_hostname = False  # локальный Caddy с самоподписанным сертификатом
            ssl_ctx.verify_mode = ssl.CERT_NONE
        ws = await websockets.connect(ws_url, ssl=ssl_ctx)
        snap = json.loads(await asyncio.wait_for(ws.recv(), 5))
        check(snap["type"] == "snapshot", "WebSocket: снимок состояния при подключении")

        await mq.subscribe(f"relay/{relay_id}/command", qos=1)

        # реле: плита включена (просадка напряжения), датчик: всё спокойно, человек рядом
        for i in range(3):
            await mq.publish(f"relay/{relay_id}/power",
                             json.dumps({"voltage": 218.9, "current": 8.14, "state": 1}))
            await mq.publish(f"device/{sensor_id}/telemetry", sensor_packet(45.0, 24.0 + i * 0.1, 120.0, 1))
            await asyncio.sleep(0.3)
        await asyncio.sleep(1.0)
        r = await http.get(f"/api/v1/homes/{home_id}/status", headers=H)
        st = r.json()
        check(st["stove_on"] is True, "плита распознана как включённая по просадке напряжения")
        check(st["state"] in ("Cooking", "Classified", "Normal"), "состояние Дома Cooking", st["state"])
        check(st["last_sensor"] and abs(st["last_sensor"]["humidity"] - 45.0) < 1e-3,
              "13-байтовый пакет разобран верно (влажность)")

        r = await http.get("/api/v1/analytics", headers=H, params={"home_id": home_id, "step": 60})
        ana = r.json()
        check(r.status_code == 200 and len(ana["points"]) >= 1, "GET /api/v1/analytics (датчик)", r.text)
        if ana.get("points"):
            p = ana["points"][-1]
            check(abs(p["humidity"] - 45.0) < 0.01 and 23.9 < p["temperature"] < 24.3,
                  "в аналитике val_1=температура, val_2=влажность", str(p))
        r = await http.get("/api/v1/analytics", headers=H,
                           params={"home_id": home_id, "device_type": "relay", "points": 50})
        check(r.status_code == 200 and r.json()["fields"] == ["voltage", "current", "state"],
              "GET /api/v1/analytics (реле)")

        print("5. ТРЕВОГА по радару")
        await mq.publish(f"device/{sensor_id}/telemetry", sensor_packet(45.0, 24.5, 125.0, 2))
        got_cmd = None
        try:
            async with asyncio.timeout(5):
                async for msg in mq.messages:
                    if msg.topic.value == f"relay/{relay_id}/command":
                        got_cmd = bytes(msg.payload).decode()
                        break
        except TimeoutError:
            pass
        check(got_cmd == "0", "сервер отправил '0' в relay/{id}/command", str(got_cmd))

        alarm_event = None
        try:
            async with asyncio.timeout(5):
                while alarm_event is None:
                    ev = json.loads(await ws.recv())
                    if ev["type"] == "alarm":
                        alarm_event = ev
        except TimeoutError:
            pass
        check(alarm_event is not None and alarm_event["reason"] == "radar_critical"
              and alarm_event["command_sent"], "WebSocket: событие alarm («красный экран»)")

        # реле выполнило команду и сообщает Off
        await mq.publish(f"relay/{relay_id}/power", json.dumps({"voltage": 230.0, "current": 0, "state": 0}))
        await asyncio.sleep(0.8)
        st = (await http.get(f"/api/v1/homes/{home_id}/status", headers=H)).json()
        check(st["state"] == "RelayOff" and st["verdict"] == "ALARM", "состояние Дома RelayOff", st["state"])
        r = await http.get(f"/api/v1/homes/{home_id}/alarms", headers=H)
        check(r.status_code == 200 and r.json() and r.json()[0]["reason"] == "radar_critical",
              "тревога записана в историю")

        print("6. Ручное управление")
        r = await http.post(f"/api/v1/homes/{home_id}/relay/command", headers=H, json={"command": 1})
        check(r.status_code == 200 and r.json()["status"]["state"] == "Idle",
              "команда 1 из приложения сбрасывает тревогу -> Idle", r.text)
        got_cmd = None
        try:
            async with asyncio.timeout(5):
                async for msg in mq.messages:
                    if msg.topic.value == f"relay/{relay_id}/command":
                        got_cmd = bytes(msg.payload).decode()
                        break
        except TimeoutError:
            pass
        check(got_cmd == "1", "реле получило '1'", str(got_cmd))
        r = await http.post(f"/api/v1/homes/{home_id}/relay/command", headers=H, json={"command": 5})
        check(r.status_code == 422, "команда 5 -> 422")
        r = await http.put(f"/api/v1/homes/{home_id}/protection", headers=H, json={"enabled": False})
        check(r.status_code == 200 and r.json()["protection_enabled"] is False, "защита выключается")
        await http.put(f"/api/v1/homes/{home_id}/protection", headers=H, json={"enabled": True})
        r = await http.get(f"/api/v1/homes/{home_id}/commands", headers=H)
        check([c["source"] for c in r.json()][:2] == ["manual", "auto"], "журнал команд: auto и manual")

        print("7. Устойчивость")
        before = (await http.get("/health")).json()["stats"]
        await mq.publish(f"device/{sensor_id}/telemetry", b"\x00" * 12)
        await mq.publish(f"relay/{relay_id}/power", b"not json")
        await mq.publish(f"device/unknown-{suffix}/telemetry", sensor_packet(1, 2, 3, 0))
        await asyncio.sleep(0.8)
        after = (await http.get("/health")).json()["stats"]
        check(after["bad_packets"] - before["bad_packets"] == 2, "битые пакеты отброшены, сервис жив")
        check(after["unknown_device_packets"] > before["unknown_device_packets"],
              "пакеты незаведённых устройств пропущены")

        print("8. OTA-заглушки")
        r = await http.get("/api/v1/firmware/check", params={"device_id": sensor_id})
        check(r.status_code == 200 and r.json()["update_available"] is False, "GET /api/v1/firmware/check")
        r = await http.get("/api/v1/firmware/download", params={"device_id": sensor_id})
        check(r.status_code == 200 and r.headers.get("x-firmware-stub") == "true",
              "GET /api/v1/firmware/download")

        await ws.close()
        if not a.keep:
            r = await http.delete(f"/api/v1/homes/{home_id}", headers=H)
            check(r.status_code == 204, "DELETE /api/v1/homes/{id}, устройства освобождены")

    print(f"\nИтог: {'всё прошло' if failures == 0 else f'ошибок: {failures}'}")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8000")
    ap.add_argument("--mqtt-host", default="localhost")
    ap.add_argument("--mqtt-port", type=int, default=1883)
    ap.add_argument("--mqtt-user")
    ap.add_argument("--mqtt-password")
    ap.add_argument("--sensor-id")
    ap.add_argument("--relay-id")
    ap.add_argument("--keep", action="store_true", help="не удалять созданный Дом")
    sys.exit(asyncio.run(main(ap.parse_args())))
