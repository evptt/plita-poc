"""Доставка событий в приложение по WebSocket («красный экран»).

В ТЗ канал не выбран (вопрос 5). WebSocket выбран потому, что Caddy его уже
пропускает и не нужен внешний сервис. Для фона на телефоне позже можно
добавить push через Firebase - события формируются в одном месте (engine.py).
"""

from __future__ import annotations

import asyncio
import json
import logging

from fastapi import WebSocket

log = logging.getLogger(__name__)


class WsHub:
    def __init__(self) -> None:
        self._by_user: dict[int, set[WebSocket]] = {}

    def add(self, user_id: int, ws: WebSocket) -> None:
        self._by_user.setdefault(user_id, set()).add(ws)

    def remove(self, user_id: int, ws: WebSocket) -> None:
        conns = self._by_user.get(user_id)
        if conns:
            conns.discard(ws)
            if not conns:
                self._by_user.pop(user_id, None)

    def connections(self) -> int:
        return sum(len(v) for v in self._by_user.values())

    async def send_to_user(self, user_id: int, event: dict) -> None:
        conns = list(self._by_user.get(user_id, ()))
        if not conns:
            return
        text = json.dumps(event, ensure_ascii=False, default=str)
        results = await asyncio.gather(*(c.send_text(text) for c in conns), return_exceptions=True)
        for conn, res in zip(conns, results):
            if isinstance(res, Exception):
                self.remove(user_id, conn)
