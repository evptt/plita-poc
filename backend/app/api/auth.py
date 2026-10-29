"""Авторизация (рис. 3): Google id_token -> внутренний JWT."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field

from ..security import AuthError, GoogleUnavailable, issue_jwt, verify_google_id_token
from .deps import CurrentUser, get_current_user

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


class GoogleLogin(BaseModel):
    id_token: str = Field(min_length=10)


class DevLogin(BaseModel):
    email: str = Field(min_length=3, max_length=200)
    name: str | None = Field(default=None, max_length=200)


class UserOut(BaseModel):
    id: int
    email: str | None
    name: str | None


class TokenOut(BaseModel):
    access_token: str
    token_type: str = "Bearer"
    expires_in: int
    user: UserOut
    is_new_user: bool


async def _login(request: Request, google_id: str, email: str | None, name: str | None) -> TokenOut:
    pool = request.app.state.pool
    async with pool.acquire() as conn:
        async with conn.transaction():
            row = await conn.fetchrow("SELECT id FROM users WHERE google_id = $1 ORDER BY id LIMIT 1",
                                      google_id)
            is_new = row is None
            if is_new:
                row = await conn.fetchrow(
                    "INSERT INTO users (google_id, email, name, created_at) VALUES ($1, $2, $3, now()) "
                    "RETURNING id", google_id, email, name)
            else:
                await conn.execute(
                    "UPDATE users SET email = COALESCE($2, email), name = COALESCE($3, name) WHERE id = $1",
                    row["id"], email, name)
    token, ttl = issue_jwt(request.app.state.settings, row["id"])
    return TokenOut(access_token=token, expires_in=ttl, is_new_user=is_new,
                    user=UserOut(id=row["id"], email=email, name=name))


@router.post("/google", response_model=TokenOut)
async def login_google(body: GoogleLogin, request: Request):
    try:
        info = await run_in_threadpool(verify_google_id_token, request.app.state.settings, body.id_token)
    except GoogleUnavailable as e:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(e)) from e
    except AuthError as e:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(e)) from e
    return await _login(request, info["google_id"], info["email"], info["name"])


@router.post("/dev", response_model=TokenOut, include_in_schema=False)
async def login_dev(body: DevLogin, request: Request):
    """Вход без Google - только для разработки, пока нет OAuth-клиента. Включается DEV_AUTH_ENABLED=true."""
    if not request.app.state.settings.dev_auth_enabled:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Not Found")
    return await _login(request, f"dev:{body.email.lower()}", body.email, body.name)


@router.get("/me", response_model=UserOut)
async def me(user: CurrentUser = Depends(get_current_user)):
    return UserOut(id=user.id, email=user.email, name=user.name)
