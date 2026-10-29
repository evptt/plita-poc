"""Дома и сопряжение (рис. 3), ручное управление реле, состояние и тревоги."""

from __future__ import annotations

import json
from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status
from pydantic import BaseModel, Field, field_validator

from .deps import CurrentUser, get_current_user, get_engine, get_own_home

router = APIRouter(prefix="/api/v1/homes", tags=["homes"])

DEVICE_ID_PATTERN = r"^[A-Za-z0-9_.:\-]{1,64}$"  # без '/', '+', '#' - они ломают MQTT-топики


class HomeCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    sensor_id: str = Field(pattern=DEVICE_ID_PATTERN)
    relay_id: str = Field(pattern=DEVICE_ID_PATTERN)
    sensor_mac: str | None = Field(default=None, max_length=32)
    relay_mac: str | None = Field(default=None, max_length=32)
    sensor_firmware: str | None = Field(default=None, max_length=32)
    relay_firmware: str | None = Field(default=None, max_length=32)

    @field_validator("relay_id")
    @classmethod
    def different_ids(cls, v, info):
        if v == info.data.get("sensor_id"):
            raise ValueError("sensor_id и relay_id должны отличаться")
        return v


class DeviceOut(BaseModel):
    id: str
    type: str
    mac_address: str | None
    firmware_version: str | None


class HomeOut(BaseModel):
    home_id: int
    name: str
    sensor_id: str | None
    relay_id: str | None
    devices: list[DeviceOut]
    state: str
    verdict: str
    protection_enabled: bool


class RelayCommand(BaseModel):
    command: int = Field(description="0 - выключить плиту, 1 - включить (и сбросить тревогу)")

    @field_validator("command")
    @classmethod
    def zero_or_one(cls, v):
        if v not in (0, 1):
            raise ValueError("command: 0 или 1")
        return v


class ProtectionIn(BaseModel):
    enabled: bool


async def _home_out(request: Request, home_id: int, name: str) -> HomeOut:
    engine = request.app.state.engine
    devs = await request.app.state.pool.fetch(
        "SELECT id, type::text AS type, mac_address, firmware_version FROM devices "
        "WHERE home_id = $1 ORDER BY type", home_id)
    st = engine.status(home_id)
    return HomeOut(
        home_id=home_id, name=name,
        sensor_id=next((d["id"] for d in devs if d["type"] == "sensor"), None),
        relay_id=next((d["id"] for d in devs if d["type"] == "relay"), None),
        devices=[DeviceOut(**dict(d)) for d in devs],
        state=st.get("state", "Idle"), verdict=st.get("verdict", "NONE"),
        protection_enabled=st.get("protection_enabled", True))


@router.post("", status_code=status.HTTP_201_CREATED, response_model=HomeOut)
async def create_home(body: HomeCreate, request: Request, user: CurrentUser = Depends(get_current_user)):
    pool = request.app.state.pool
    engine = get_engine(request)
    async with pool.acquire() as conn:
        async with conn.transaction():
            for dev_id, dev_type in ((body.sensor_id, "sensor"), (body.relay_id, "relay")):
                row = await conn.fetchrow(
                    "SELECT d.type::text AS type, d.home_id, h.user_id FROM devices d "
                    "LEFT JOIN homes h ON h.id = d.home_id WHERE d.id = $1 FOR UPDATE OF d", dev_id)
                if row is None:
                    continue
                if row["type"] != dev_type:
                    raise HTTPException(status.HTTP_409_CONFLICT,
                                        f"устройство {dev_id} заведено как {row['type']}, а не {dev_type}")
                if row["home_id"] is not None:
                    who = "другому вашему Дому" if row["user_id"] == user.id else "другому пользователю"
                    raise HTTPException(status.HTTP_409_CONFLICT,
                                        f"устройство {dev_id} уже привязано к {who}")

            home = await conn.fetchrow(
                "INSERT INTO homes (user_id, name) VALUES ($1, $2) RETURNING id", user.id, body.name)
            home_id = home["id"]
            for dev_id, dev_type, mac, fw in (
                    (body.sensor_id, "sensor", body.sensor_mac, body.sensor_firmware),
                    (body.relay_id, "relay", body.relay_mac, body.relay_firmware)):
                await conn.execute("""
                    INSERT INTO devices (id, home_id, type, mac_address, firmware_version)
                    VALUES ($1, $2, $3, $4, $5)
                    ON CONFLICT (id) DO UPDATE SET home_id = EXCLUDED.home_id,
                        mac_address = COALESCE(EXCLUDED.mac_address, devices.mac_address),
                        firmware_version = COALESCE(EXCLUDED.firmware_version, devices.firmware_version)
                """, dev_id, home_id, dev_type, mac, fw)
            await conn.execute("INSERT INTO home_state (home_id) VALUES ($1) ON CONFLICT DO NOTHING",
                               home_id)
    await engine.reload_home(home_id)
    return await _home_out(request, home_id, body.name)


@router.get("", response_model=list[HomeOut])
async def list_homes(request: Request, user: CurrentUser = Depends(get_current_user)):
    rows = await request.app.state.pool.fetch(
        "SELECT id, name FROM homes WHERE user_id = $1 ORDER BY id", user.id)
    return [await _home_out(request, r["id"], r["name"]) for r in rows]


@router.get("/{home_id}", response_model=HomeOut)
async def get_home(request: Request, home=Depends(get_own_home)):
    return await _home_out(request, home["id"], home["name"])


@router.delete("/{home_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_home(request: Request, home=Depends(get_own_home)):
    """Удаляет Дом. Устройства и их история остаются, но отвязываются (их можно привязать заново)."""
    pool = request.app.state.pool
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("UPDATE devices SET home_id = NULL WHERE home_id = $1", home["id"])
            await conn.execute("DELETE FROM homes WHERE id = $1", home["id"])
    await get_engine(request).reload_home(home["id"])
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/{home_id}/status")
async def home_status(request: Request, home=Depends(get_own_home)):
    """Текущее состояние Дома: Idle/Cooking/Classified/Normal/Alarm/RelayOff, вердикт, процесс,
    последние показания. Запасной вариант к WebSocket - можно опрашивать."""
    return get_engine(request).status(home["id"])


@router.post("/{home_id}/relay/command")
async def relay_command(body: RelayCommand, request: Request, home=Depends(get_own_home),
                        user: CurrentUser = Depends(get_current_user)):
    engine = get_engine(request)
    if engine.homes.get(home["id"]) is None or engine.homes[home["id"]].relay_id is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "к Дому не привязано реле")
    ok = await engine.manual_command(home["id"], body.command, user.id)
    if not ok:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "MQTT-брокер недоступен, команда не отправлена")
    return {"home_id": home["id"], "relay_id": engine.homes[home["id"]].relay_id,
            "command": body.command, "sent": True, "status": engine.status(home["id"])}


@router.put("/{home_id}/protection")
async def set_protection(body: ProtectionIn, request: Request, home=Depends(get_own_home)):
    """Деактивация/активация защиты. При выключенной защите сервер продолжает анализ и шлёт
    тревоги в приложение, но сам не отправляет реле команду 0."""
    engine = get_engine(request)
    await engine.set_protection(home["id"], body.enabled)
    return engine.status(home["id"])


@router.get("/{home_id}/alarms")
async def list_alarms(request: Request, home=Depends(get_own_home),
                      limit: int = Query(50, ge=1, le=500)):
    rows = await request.app.state.pool.fetch(
        "SELECT id, created_at, reason, details::text AS details, command_sent FROM alarms "
        "WHERE home_id = $1 ORDER BY created_at DESC LIMIT $2", home["id"], limit)
    return [{"id": r["id"], "at": r["created_at"], "reason": r["reason"],
             "details": json.loads(r["details"]), "command_sent": r["command_sent"]} for r in rows]


@router.get("/{home_id}/commands")
async def list_commands(request: Request, home=Depends(get_own_home),
                        limit: int = Query(50, ge=1, le=500)):
    rows = await request.app.state.pool.fetch(
        "SELECT id, created_at, relay_id, command, source, user_id, reason FROM relay_commands "
        "WHERE home_id = $1 ORDER BY created_at DESC LIMIT $2", home["id"], limit)
    return [{"id": r["id"], "at": r["created_at"], "relay_id": r["relay_id"], "command": r["command"],
             "source": r["source"], "user_id": r["user_id"], "reason": r["reason"]} for r in rows]
