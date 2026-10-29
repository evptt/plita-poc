-- Базовая схема (раздел 5 ТЗ). На уже созданной базе (infra/db/init/001_schema.sql)
-- ничего не меняет благодаря IF NOT EXISTS.
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
