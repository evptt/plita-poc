"""WebSocket /api/v1/ws - события для приложения («красный экран»).

Подключение: wss://<домен>/api/v1/ws?token=<JWT>
Сразу после подключения приходит {"type": "snapshot", "homes": [...]}, дальше события:
  {"type": "alarm", "home_id", "reason", "details", "command_sent", "at"}  - ТРЕВОГА
  {"type": "state", "home_id", "state", "verdict", "process", ...}           - смена состояния
  {"type": "sensor_offline" | "relay_offline" | "sensor_online" | "relay_online", "home_id", ...}
Клиент может слать "ping" - ответ "pong".
"""

from __future__ import annotations

import json

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, status

from ..security import AuthError, decode_jwt

router = APIRouter()


@router.websocket("/api/v1/ws")
async def ws_events(websocket: WebSocket):
    app = websocket.app
    token = websocket.query_params.get("token")
    auth = websocket.headers.get("authorization", "")
    if not token and auth.lower().startswith("bearer "):
        token = auth[7:]
    try:
        user_id = decode_jwt(app.state.settings, token or "")
    except AuthError:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    await websocket.accept()
    hub = app.state.hub
    engine = app.state.engine
    hub.add(user_id, websocket)
    try:
        homes = [hid for hid, h in engine.homes.items() if h.user_id == user_id]
        await websocket.send_text(json.dumps(
            {"type": "snapshot", "homes": [engine.status(h) for h in homes]},
            ensure_ascii=False, default=str))
        while True:
            msg = await websocket.receive_text()
            if msg.strip().lower() == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        pass
    finally:
        hub.remove(user_id, websocket)
