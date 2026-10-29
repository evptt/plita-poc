"""Заглушки OTA (раздел 10 разбора ТЗ: реальный OTA в PoC не делаем)."""

from __future__ import annotations

from fastapi import APIRouter, Query, Request, Response

router = APIRouter(prefix="/api/v1/firmware", tags=["firmware"])


@router.get("/check")
async def firmware_check(request: Request,
                         device_id: str | None = Query(None),
                         current_version: str | None = Query(None)):
    current = current_version
    if current is None and device_id:
        current = await request.app.state.pool.fetchval(
            "SELECT firmware_version FROM devices WHERE id = $1", device_id)
    latest = request.app.state.settings.firmware_latest_version
    return {
        "device_id": device_id,
        "current_version": current,
        "latest_version": latest,
        "update_available": False,
        "download_url": None,
        "stub": True,
    }


@router.get("/download")
async def firmware_download(request: Request, device_id: str | None = Query(None),
                            version: str | None = Query(None)):
    latest = request.app.state.settings.firmware_latest_version
    return Response(content=b"", media_type="application/octet-stream",
                    headers={"X-Firmware-Version": version or latest, "X-Firmware-Stub": "true",
                             "Content-Disposition": f'attachment; filename="firmware-{version or latest}.bin"'})
