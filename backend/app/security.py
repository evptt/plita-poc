"""JWT (внутренний сессионный токен) и проверка Google id_token."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import jwt

from .config import Settings

JWT_ALG = "HS256"
JWT_ISSUER = "plita-backend"


class AuthError(Exception):
    pass


class GoogleUnavailable(AuthError):
    """Проблема на стороне сервера (нет GOOGLE_CLIENT_ID или Google недоступен) -> 503."""


def issue_jwt(settings: Settings, user_id: int) -> tuple[str, int]:
    now = datetime.now(timezone.utc)
    ttl = timedelta(hours=settings.jwt_ttl_hours)
    payload = {"sub": str(user_id), "iat": now, "exp": now + ttl, "iss": JWT_ISSUER}
    return jwt.encode(payload, settings.jwt_secret, algorithm=JWT_ALG), int(ttl.total_seconds())


def decode_jwt(settings: Settings, token: str) -> int:
    try:
        payload = jwt.decode(token, settings.jwt_secret, algorithms=[JWT_ALG], issuer=JWT_ISSUER,
                             options={"require": ["sub", "exp"]})
        return int(payload["sub"])
    except (jwt.PyJWTError, ValueError, KeyError) as e:
        raise AuthError("недействительный или просроченный токен") from e


def verify_google_id_token(settings: Settings, token: str) -> dict:
    """Проверка подписи, срока и audience (Web Client ID) у Google id_token.

    Блокирующая функция (скачивает сертификаты Google) - вызывать в threadpool.
    """
    from google.auth import exceptions as google_exceptions
    from google.auth.transport import requests as google_requests
    from google.oauth2 import id_token

    if not settings.google_client_ids:
        raise GoogleUnavailable("GOOGLE_CLIENT_ID не задан на сервере")
    try:
        info = id_token.verify_oauth2_token(token, google_requests.Request(), audience=None,
                                            clock_skew_in_seconds=10)
    except google_exceptions.TransportError as e:
        raise GoogleUnavailable(f"не удалось получить ключи Google: {e}") from e
    except (ValueError, google_exceptions.GoogleAuthError) as e:
        raise AuthError(f"Google id_token не прошёл проверку: {e}") from e
    if info.get("aud") not in settings.google_client_ids:
        raise AuthError("Google id_token выписан для другого приложения (aud)")
    if info.get("iss") not in ("accounts.google.com", "https://accounts.google.com"):
        raise AuthError("неверный издатель Google id_token")
    if not info.get("sub"):
        raise AuthError("в Google id_token нет sub")
    return {"google_id": info["sub"], "email": info.get("email"), "name": info.get("name")}
