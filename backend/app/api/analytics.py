"""GET /api/v1/analytics - агрегированные точки для графиков (раздел 9 разбора ТЗ)."""

from __future__ import annotations

import math
from datetime import datetime, timedelta, timezone
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from .deps import CurrentUser, get_current_user

router = APIRouter(prefix="/api/v1", tags=["analytics"])

MAX_SPAN = timedelta(days=31)
MAX_POINTS = 2000
DEFAULT_POINTS = 300

# Раскладка val_1..val_4 по типам устройств (таблица из раздела 4 разбора ТЗ)
FIELDS = {
    "sensor": ("temperature", "humidity", "voc", "radar"),
    "relay": ("voltage", "current", None, "state"),
}


def _utc(dt: datetime) -> datetime:
    return dt.replace(tzinfo=timezone.utc) if dt.tzinfo is None else dt.astimezone(timezone.utc)


def _iso(dt: datetime) -> str:
    return _utc(dt).isoformat().replace("+00:00", "Z")


@router.get("/analytics")
async def analytics(
    request: Request,
    user: CurrentUser = Depends(get_current_user),
    home_id: int | None = Query(None, description="Дом пользователя; вместе с device_type"),
    device_type: Literal["sensor", "relay"] = Query("sensor", description="sensor или relay"),
    device_id: str | None = Query(None, description="Вместо home_id можно указать устройство напрямую"),
    from_: datetime | None = Query(None, alias="from", description="ISO 8601, по умолчанию 1 час назад"),
    to: datetime | None = Query(None, description="ISO 8601, по умолчанию сейчас"),
    step: int | None = Query(None, ge=1, le=86400, description="Шаг агрегации, секунды"),
    points: int = Query(DEFAULT_POINTS, ge=10, le=MAX_POINTS,
                        description="Желаемое число точек, если step не задан"),
):
    """Средние значения за каждый интервал (радар и статус реле - максимум за интервал,
    т.е. «было ли движение / было ли включено»)."""
    pool = request.app.state.pool

    if device_id is None and home_id is None:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "нужен home_id или device_id")

    if device_id is not None:
        dev = await pool.fetchrow(
            "SELECT d.id, d.type::text AS type, d.home_id FROM devices d "
            "JOIN homes h ON h.id = d.home_id WHERE d.id = $1 AND h.user_id = $2", device_id, user.id)
    else:
        dev = await pool.fetchrow(
            "SELECT d.id, d.type::text AS type, d.home_id FROM devices d "
            "JOIN homes h ON h.id = d.home_id WHERE h.id = $1 AND h.user_id = $2 AND d.type::text = $3",
            home_id, user.id, device_type)
    if dev is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "устройство не найдено среди ваших Домов")

    now = datetime.now(timezone.utc)
    t_to = _utc(to) if to else now
    t_from = _utc(from_) if from_ else t_to - timedelta(hours=1)
    if t_from >= t_to:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "from должен быть раньше to")
    if t_to - t_from > MAX_SPAN:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "период не больше 31 дня")

    span_s = (t_to - t_from).total_seconds()
    step_s = step or max(1, math.ceil(span_s / points))
    if span_s / step_s > MAX_POINTS:
        step_s = math.ceil(span_s / MAX_POINTS)

    rows = await pool.fetch("""
        SELECT date_bin(make_interval(secs => $4), "timestamp"::timestamptz,
                        TIMESTAMPTZ '2000-01-01 00:00:00+00') AS bucket,
               avg(val_1) AS v1, avg(val_2) AS v2, avg(val_3) AS v3, max(val_4) AS v4,
               count(*) AS n
        FROM telemetry_history
        WHERE device_id = $1 AND "timestamp" >= $2::timestamptz AND "timestamp" < $3::timestamptz
        GROUP BY bucket ORDER BY bucket""",
        dev["id"], t_from, t_to, float(step_s))

    names = FIELDS[dev["type"]]
    out_points = []
    for r in rows:
        p = {"t": _iso(r["bucket"]), "n": r["n"]}
        for name, key in zip(names, ("v1", "v2", "v3", "v4")):
            if name is None:
                continue
            v = r[key]
            if v is not None and name in ("radar", "state"):
                v = int(v)
            elif v is not None:
                v = round(v, 4)
            p[name] = v
        out_points.append(p)

    return {
        "device_id": dev["id"],
        "device_type": dev["type"],
        "home_id": dev["home_id"],
        "from": _iso(t_from),
        "to": _iso(t_to),
        "step_s": step_s,
        "fields": [n for n in names if n is not None],
        "points": out_points,
    }
