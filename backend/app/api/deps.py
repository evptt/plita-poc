from __future__ import annotations

from dataclasses import dataclass

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from ..engine import Engine
from ..security import AuthError, decode_jwt

bearer = HTTPBearer(auto_error=False)


@dataclass
class CurrentUser:
    id: int
    google_id: str
    email: str | None
    name: str | None


def get_engine(request: Request) -> Engine:
    return request.app.state.engine


async def get_current_user(request: Request,
                           creds: HTTPAuthorizationCredentials | None = Depends(bearer)) -> CurrentUser:
    if creds is None or creds.scheme.lower() != "bearer":
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "нужен заголовок Authorization: Bearer <JWT>",
                            headers={"WWW-Authenticate": "Bearer"})
    try:
        user_id = decode_jwt(request.app.state.settings, creds.credentials)
    except AuthError as e:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(e),
                            headers={"WWW-Authenticate": "Bearer"}) from e
    row = await request.app.state.pool.fetchrow(
        "SELECT id, google_id, email, name FROM users WHERE id = $1", user_id)
    if row is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "пользователь не найден")
    return CurrentUser(row["id"], row["google_id"], row["email"], row["name"])


async def get_own_home(home_id: int, request: Request,
                       user: CurrentUser = Depends(get_current_user)):
    row = await request.app.state.pool.fetchrow(
        "SELECT id, user_id, name FROM homes WHERE id = $1", home_id)
    if row is None or row["user_id"] != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Дом не найден")
    return row
