-- Схема БД по ТЗ, раздел 5.
-- Выполняется автоматически ОДИН раз, при первом запуске пустой базы.
-- Дальше схемой управляют бэкендеры своими миграциями.

CREATE TABLE users (
    id          SERIAL PRIMARY KEY,
    google_id   TEXT NOT NULL UNIQUE,
    email       TEXT,
    name        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE homes (
    id       SERIAL PRIMARY KEY,
    user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name     TEXT NOT NULL
);
CREATE INDEX homes_user_id_idx ON homes (user_id);

CREATE TYPE device_type AS ENUM ('sensor', 'relay');

CREATE TABLE devices (
    id                TEXT PRIMARY KEY,            -- UUID-строка устройства
    home_id           INTEGER REFERENCES homes(id) ON DELETE SET NULL,
    type              device_type NOT NULL,
    mac_address       TEXT,
    firmware_version  TEXT
);
CREATE INDEX devices_home_id_idx ON devices (home_id);

-- sensor: val_1=температура, val_2=влажность, val_3=VOC,  val_4=радар
-- relay:  val_1=напряжение,  val_2=ток,       val_3=NULL, val_4=статус реле
CREATE TABLE telemetry_history (
    id         BIGSERIAL PRIMARY KEY,
    device_id  TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    "timestamp" TIMESTAMPTZ NOT NULL DEFAULT now(),
    val_1      DOUBLE PRECISION,
    val_2      DOUBLE PRECISION,
    val_3      DOUBLE PRECISION,
    val_4      DOUBLE PRECISION
);
-- главный индекс под запросы "данные устройства за период" (графики)
CREATE INDEX telemetry_device_ts_idx ON telemetry_history (device_id, "timestamp" DESC);
CREATE INDEX telemetry_ts_idx ON telemetry_history ("timestamp");
