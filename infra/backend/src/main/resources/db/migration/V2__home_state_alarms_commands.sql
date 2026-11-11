-- Состояние Домов, история тревог, журнал команд реле.
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
