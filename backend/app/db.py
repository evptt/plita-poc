"""PostgreSQL: пул соединений, миграции, проверка схемы.

Базовые таблицы (users, homes, devices, telemetry_history) уже созданы
infra/db/init/001_schema.sql на сервере. Миграции здесь написаны так, что
на существующей базе ничего не ломают (IF NOT EXISTS), а на пустой - создают
схему по разделу 5 ТЗ. Применённые версии хранятся в schema_migrations.
"""

from __future__ import annotations

import asyncio
import logging

import asyncpg

log = logging.getLogger(__name__)

MIGRATIONS: list[tuple[int, str, str]] = [
    (1, "base schema (раздел 5 ТЗ)", """
        CREATE TABLE IF NOT EXISTS users (
            id          SERIAL PRIMARY KEY,
            google_id   TEXT NOT NULL,
            email       TEXT,
            name        TEXT,
            created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX IF NOT EXISTS idx_users_google_id ON users (google_id);

        CREATE TABLE IF NOT EXISTS homes (
            id       SERIAL PRIMARY KEY,
            user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            name     TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS devices (
            id                TEXT PRIMARY KEY,
            home_id           INTEGER REFERENCES homes(id) ON DELETE SET NULL,
            type              TEXT NOT NULL CHECK (type IN ('sensor', 'relay')),
            mac_address       TEXT,
            firmware_version  TEXT
        );

        CREATE TABLE IF NOT EXISTS telemetry_history (
            id           BIGSERIAL PRIMARY KEY,
            device_id    TEXT NOT NULL REFERENCES devices(id),
            "timestamp"  TIMESTAMPTZ NOT NULL DEFAULT now(),
            val_1        DOUBLE PRECISION,
            val_2        DOUBLE PRECISION,
            val_3        DOUBLE PRECISION,
            val_4        DOUBLE PRECISION
        );
        CREATE INDEX IF NOT EXISTS idx_telemetry_device_ts
            ON telemetry_history (device_id, "timestamp");
    """),
    (2, "состояние Домов, тревоги, журнал команд", """
        -- устройство может существовать без Дома (заведено заранее, Дом удалён)
        ALTER TABLE devices ALTER COLUMN home_id DROP NOT NULL;

        -- уникальность google_id; если в базе уже есть дубли - не падаем, а предупреждаем
        DO $$
        BEGIN
            CREATE UNIQUE INDEX IF NOT EXISTS uq_users_google_id ON users (google_id);
        EXCEPTION WHEN unique_violation THEN
            RAISE WARNING 'users.google_id содержит дубли, уникальный индекс не создан';
        END $$;

        CREATE INDEX IF NOT EXISTS idx_homes_user ON homes (user_id);
        CREATE INDEX IF NOT EXISTS idx_devices_home ON devices (home_id);

        -- текущее состояние Дома (рис. 5) и флаг защиты: переживает перезапуск сервиса
        CREATE TABLE IF NOT EXISTS home_state (
            home_id             INTEGER PRIMARY KEY REFERENCES homes(id) ON DELETE CASCADE,
            state               TEXT NOT NULL DEFAULT 'Idle',
            verdict             TEXT NOT NULL DEFAULT 'NONE',
            process             TEXT,
            reason              TEXT,
            protection_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
            updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
        );

        -- история тревог
        CREATE TABLE IF NOT EXISTS alarms (
            id             BIGSERIAL PRIMARY KEY,
            home_id        INTEGER NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
            created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
            reason         TEXT NOT NULL,
            details        JSONB NOT NULL DEFAULT '{}'::jsonb,
            command_sent   BOOLEAN NOT NULL DEFAULT FALSE
        );
        CREATE INDEX IF NOT EXISTS idx_alarms_home_ts ON alarms (home_id, created_at DESC);

        -- журнал команд реле (автоматических и ручных)
        CREATE TABLE IF NOT EXISTS relay_commands (
            id          BIGSERIAL PRIMARY KEY,
            relay_id    TEXT NOT NULL,
            home_id     INTEGER REFERENCES homes(id) ON DELETE SET NULL,
            command     SMALLINT NOT NULL CHECK (command IN (0, 1)),
            source      TEXT NOT NULL,          -- auto | manual
            user_id     INTEGER REFERENCES users(id) ON DELETE SET NULL,
            reason      TEXT,
            created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX IF NOT EXISTS idx_relay_commands_home_ts ON relay_commands (home_id, created_at DESC);
    """),
]

REQUIRED_COLUMNS = {
    "users": {"id", "google_id", "email", "name", "created_at"},
    "homes": {"id", "user_id", "name"},
    "devices": {"id", "home_id", "type", "mac_address", "firmware_version"},
    "telemetry_history": {"id", "device_id", "timestamp", "val_1", "val_2", "val_3", "val_4"},
}


async def create_pool(dsn: str, attempts: int = 30, delay: float = 2.0) -> asyncpg.Pool:
    """База может подниматься дольше сервиса - ждём."""
    last_error: Exception | None = None
    for i in range(1, attempts + 1):
        try:
            pool = await asyncpg.create_pool(dsn, min_size=1, max_size=10, command_timeout=30)
            log.info("PostgreSQL подключён")
            return pool
        except (OSError, asyncpg.PostgresError) as e:
            last_error = e
            log.warning("PostgreSQL недоступен (попытка %d/%d): %s", i, attempts, e)
            await asyncio.sleep(delay)
    raise RuntimeError(f"Не удалось подключиться к PostgreSQL: {last_error}")


async def migrate(pool: asyncpg.Pool) -> None:
    async with pool.acquire() as conn:
        # защита от параллельного запуска двух копий сервиса
        await conn.execute("SELECT pg_advisory_lock(424242)")
        try:
            await conn.execute("""
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version     INTEGER PRIMARY KEY,
                    name        TEXT NOT NULL,
                    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                )""")
            applied = {r["version"] for r in await conn.fetch("SELECT version FROM schema_migrations")}
            for version, name, sql in MIGRATIONS:
                if version in applied:
                    continue
                async with conn.transaction():
                    await conn.execute(sql)
                    await conn.execute(
                        "INSERT INTO schema_migrations (version, name) VALUES ($1, $2)", version, name)
                log.info("Миграция %d применена: %s", version, name)
        finally:
            await conn.execute("SELECT pg_advisory_unlock(424242)")


async def check_schema(pool: asyncpg.Pool) -> None:
    """Падаем сразу и понятно, если колонки в базе называются не так, как ждёт код."""
    rows = await pool.fetch("""
        SELECT table_name, column_name FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = ANY($1::text[])""",
        list(REQUIRED_COLUMNS))
    present: dict[str, set[str]] = {}
    for r in rows:
        present.setdefault(r["table_name"], set()).add(r["column_name"])
    problems = []
    for table, cols in REQUIRED_COLUMNS.items():
        missing = cols - present.get(table, set())
        if missing:
            problems.append(f"{table}: нет колонок {sorted(missing)}")
    if problems:
        raise RuntimeError("Схема БД не совпадает с ожидаемой (раздел 5 ТЗ): " + "; ".join(problems))


async def seed_devices(pool: asyncpg.Pool, devices: tuple[tuple[str, str], ...]) -> None:
    """Заводим ID эмулятора, чтобы его данные писались в историю (см. отчёт по инфраструктуре)."""
    for dev_id, dev_type in devices:
        await pool.execute(
            "INSERT INTO devices (id, type) VALUES ($1, $2) ON CONFLICT (id) DO NOTHING",
            dev_id, dev_type)
    if devices:
        log.info("Устройства заведены (если их не было): %s", ", ".join(d for d, _ in devices))
