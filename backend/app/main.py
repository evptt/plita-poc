"""Точка входа бэкенда «Контроль плиты» (PoC).

Контракт сервиса из отчёта по инфраструктуре:
  * слушать 0.0.0.0:8000 (HTTPS делает Caddy);
  * GET /health -> 200 (по нему автодеплой проверяет выкатку);
  * логи в stdout.
"""

from __future__ import annotations

import asyncio
import logging
import sys
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import __version__
from .api import analytics, auth, firmware, homes, realtime
from .config import AnalysisParams, Settings
from .db import check_schema, create_pool, migrate, seed_devices
from .engine import Engine
from .mqtt import MqttManager
from .ws import WsHub

log = logging.getLogger("plita")


def setup_logging(level: str) -> None:
    logging.basicConfig(
        stream=sys.stdout, level=level,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s")
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = Settings.from_env()
    setup_logging(settings.log_level)
    params = AnalysisParams.load(settings.analysis_params_path)
    log.info("Старт бэкенда %s; параметры анализа: %s", __version__, settings.analysis_params_path)
    if not settings.google_client_ids:
        log.warning("GOOGLE_CLIENT_ID пуст: POST /api/v1/auth/google будет отвечать 503")

    pool = await create_pool(settings.database_url)
    await migrate(pool)
    await check_schema(pool)
    await seed_devices(pool, settings.seed_devices)

    hub = WsHub()
    engine = Engine(pool, settings, params, hub)
    await engine.load()
    mqtt = MqttManager(settings, engine.handle_message)
    engine.mqtt = mqtt

    app.state.settings = settings
    app.state.params = params
    app.state.pool = pool
    app.state.hub = hub
    app.state.engine = engine
    app.state.mqtt = mqtt

    tasks = [asyncio.create_task(mqtt.run(), name="mqtt"),
             asyncio.create_task(engine.watchdog_loop(), name="watchdog")]
    try:
        yield
    finally:
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await pool.close()
        log.info("Бэкенд остановлен")


app = FastAPI(
    title="Plita PoC backend",
    version=__version__,
    description="Серверный бэкенд системы автономного контроля плиты (PoC)",
    lifespan=lifespan,
)

app.include_router(auth.router)
app.include_router(homes.router)
app.include_router(analytics.router)
app.include_router(firmware.router)
app.include_router(realtime.router)


@app.get("/health", tags=["service"])
async def health():
    """Всегда 200, пока процесс жив и база подключена. Состояние MQTT - для информации."""
    state = app.state
    db_ok = True
    try:
        await state.pool.fetchval("SELECT 1")
    except Exception:  # noqa: BLE001
        db_ok = False
    return {
        "status": "ok",
        "version": __version__,
        "db": db_ok,
        "mqtt": state.mqtt.connected,
        "ws_clients": state.hub.connections(),
        "stats": state.engine.stats,
    }
