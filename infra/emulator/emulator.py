"""Эмулятор датчика и силового реле — чтобы бэкенд можно было разрабатывать без железа.

Датчик: device/{SENSOR_ID}/telemetry, 13 байт раз в SENSOR_INTERVAL сек:
    float влажность, float температура, float VOC, uint8 радар.
    Порядок байт little-endian — подтверждено железячниками (ESP32-C3).
Реле: relay/{RELAY_ID}/power раз в секунду, JSON {"voltage", "current", "state"}.
    Это только замена для разработки: настоящий автомат Tongou TO-Q-SY2-JWT остаётся на прошивке Tuya,
    бэкенд забирает его данные и шлёт команды через Tuya Cloud OpenAPI, к нашему брокеру автомат не подключается.
Реле слушает relay/{RELAY_ID}/command: "0" — выключить плиту, "1" — включить.
"""
import json
import os
import random
import struct
import time

import paho.mqtt.client as mqtt

HOST = os.getenv("MQTT_HOST", "localhost")
PORT = int(os.getenv("MQTT_PORT", "1883"))
USER = os.getenv("MQTT_USER", "device")
PASSWORD = os.getenv("MQTT_PASSWORD", "")
SENSOR_ID = os.getenv("SENSOR_ID", "emu-sensor-1")
RELAY_ID = os.getenv("RELAY_ID", "emu-relay-1")
SENSOR_INTERVAL = float(os.getenv("SENSOR_INTERVAL", "10"))

relay_on = True


def on_connect(client, userdata, flags, reason_code, properties):
    print(f"connected to {HOST}:{PORT}: {reason_code}")
    client.subscribe(f"relay/{RELAY_ID}/command")


def on_message(client, userdata, msg):
    global relay_on
    payload = msg.payload.decode(errors="replace").strip()
    if payload in ("0", "1"):
        relay_on = payload == "1"
        print(f"COMMAND received: {payload} -> relay {'ON' if relay_on else 'OFF'}")
    else:
        print(f"unknown command payload: {msg.payload!r}")


def main():
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"emulator-{RELAY_ID}")
    client.username_pw_set(USER, PASSWORD)
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(HOST, PORT, keepalive=30)
    client.loop_start()

    temp, hum, voc = 24.0, 45.0, 120.0
    last_sensor = 0.0
    while True:
        # плита включена — среда понемногу греется, выключена — остывает
        if relay_on:
            temp += random.uniform(0.0, 0.3)
            hum += random.uniform(-0.1, 0.4)
            voc += random.uniform(0.0, 2.0)
        else:
            temp = max(22.0, temp - 0.1)
            hum = max(40.0, hum - 0.1)
            voc = max(100.0, voc - 1.0)

        # реле: 1 Гц, при работающих ТЭНах напряжение проседает
        voltage = random.uniform(214, 222) if relay_on else random.uniform(228, 232)
        current = random.uniform(6.0, 9.0) if relay_on else 0.0
        power = {"voltage": round(voltage, 1), "current": round(current, 2), "state": int(relay_on)}
        client.publish(f"relay/{RELAY_ID}/power", json.dumps(power))

        now = time.time()
        if now - last_sensor >= SENSOR_INTERVAL:
            radar = random.choice([0, 0, 0, 1])  # 0 — движения нет, 1 — человек рядом (условно)
            packet = struct.pack("<fffB", hum, temp, voc, radar)
            assert len(packet) == 13
            client.publish(f"device/{SENSOR_ID}/telemetry", packet)
            print(f"sensor: hum={hum:.1f} temp={temp:.1f} voc={voc:.0f} radar={radar} | relay: {power}")
            last_sensor = now

        time.sleep(1)


if __name__ == "__main__":
    main()
