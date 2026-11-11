-- Автомат Tuya, push-токены Firebase, чистка дублирующихся индексов.

-- Через что бэкенд говорит с устройством:
--   mqtt - наш брокер (датчик, эмулятор реле);
--   tuya - автомат Tongou на заводской прошивке Tuya, данные и команды через Tuya Cloud OpenAPI.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS platform TEXT;
UPDATE devices SET platform = 'mqtt' WHERE platform IS NULL;
ALTER TABLE devices ALTER COLUMN platform SET DEFAULT 'mqtt';
ALTER TABLE devices ALTER COLUMN platform SET NOT NULL;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'devices_platform_check') THEN
        ALTER TABLE devices ADD CONSTRAINT devices_platform_check CHECK (platform IN ('mqtt', 'tuya'));
    END IF;
END $$;

-- FCM-токены устройств пользователя: сюда шлётся push «красного экрана»
CREATE TABLE IF NOT EXISTS push_tokens (
    token       TEXT PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform    TEXT,                   -- android | ios | web
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_push_tokens_user ON push_tokens (user_id);

-- V1/V2 поверх infra/db/init/001_schema.sql создали вторые копии уже существующих индексов.
-- Лишний индекс на telemetry_history замедляет самую частую запись (реле). Удаляем свои копии,
-- только если индекс из 001_schema.sql на месте.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'telemetry_device_ts_idx') THEN
        DROP INDEX IF EXISTS idx_telemetry_device_ts;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'homes_user_id_idx') THEN
        DROP INDEX IF EXISTS idx_homes_user;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'devices_home_id_idx') THEN
        DROP INDEX IF EXISTS idx_devices_home;
    END IF;
    -- users.google_id: в 001_schema.sql уже UNIQUE (users_google_id_key), V2 добавила uq_users_google_id
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'users_google_id_key') THEN
        DROP INDEX IF EXISTS uq_users_google_id;
    END IF;
    -- обычный индекс не нужен, если есть уникальный
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = current_schema()
               AND indexname IN ('users_google_id_key', 'uq_users_google_id')) THEN
        DROP INDEX IF EXISTS idx_users_google_id;
    END IF;
END $$;
