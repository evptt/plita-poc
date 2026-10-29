"""MQTT-клиент бэкенда: подписка на телеметрию и публикация команд реле."""

from __future__ import annotations

import asyncio
import logging
from typing import Awaitable, Callable

import aiomqtt

from .config import Settings

log = logging.getLogger(__name__)

SUBSCRIPTIONS = ("device/+/telemetry", "relay/+/power")

Handler = Callable[[str, bytes], Awaitable[None]]


class MqttUnavailable(RuntimeError):
    pass


class MqttManager:
    def __init__(self, settings: Settings, handler: Handler):
        self.s = settings
        self.handler = handler
        self._client: aiomqtt.Client | None = None
        self.connected = False

    async def run(self) -> None:
        """Подключение с автоматическим переподключением."""
        backoff = 1.0
        while True:
            try:
                async with aiomqtt.Client(
                    hostname=self.s.mqtt_host,
                    port=self.s.mqtt_port,
                    username=self.s.mqtt_user or None,
                    password=self.s.mqtt_password or None,
                    identifier=self.s.mqtt_client_id,
                    keepalive=30,
                ) as client:
                    self._client = client
                    self.connected = True
                    backoff = 1.0
                    for topic in SUBSCRIPTIONS:
                        await client.subscribe(topic, qos=0)
                    log.info("MQTT подключён к %s:%s, подписки: %s",
                             self.s.mqtt_host, self.s.mqtt_port, ", ".join(SUBSCRIPTIONS))
                    async for message in client.messages:
                        payload = message.payload
                        if isinstance(payload, str):
                            payload = payload.encode()
                        elif not isinstance(payload, (bytes, bytearray)):
                            payload = str(payload).encode()
                        try:
                            await self.handler(message.topic.value, bytes(payload))
                        except Exception:  # одно битое сообщение не должно ронять подписку
                            log.exception("Ошибка обработки сообщения %s", message.topic.value)
            except asyncio.CancelledError:
                raise
            except aiomqtt.MqttError as e:
                log.warning("MQTT: соединение потеряно (%s), повтор через %.0f с", e, backoff)
            finally:
                self._client = None
                self.connected = False
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 30.0)

    async def publish(self, topic: str, payload: str | bytes, qos: int = 1) -> None:
        client = self._client
        if client is None:
            raise MqttUnavailable("нет соединения с MQTT-брокером")
        await client.publish(topic, payload, qos=qos)

    async def send_relay_command(self, relay_id: str, command: int) -> None:
        if command not in (0, 1):
            raise ValueError("команда реле: 0 или 1")
        await self.publish(f"relay/{relay_id}/command", str(command))
