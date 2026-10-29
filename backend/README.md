# Бэкенд PoC «Контроль плиты»

Сервис на место заглушки в `infra/backend/`. Python 3.12 + FastAPI, PostgreSQL (asyncpg), MQTT (aiomqtt).
Занимает ~70–90 МБ RAM, тариф сервера менять не нужно.

## Как поставить

1. Заменить содержимое `infra/backend/` содержимым этой папки.
2. Ничего в `docker-compose.yml` менять не нужно: используются уже приходящие переменные
   `DATABASE_URL`, `MQTT_HOST`, `MQTT_PORT`, `MQTT_USER`, `MQTT_PASSWORD`, `JWT_SECRET`, `GOOGLE_CLIENT_ID`.
3. Локально: `docker compose up -d --build`, затем `curl -k https://localhost/health`.
4. Ветка → pull request → мерж в `main` → автодеплой сам проверит `/health`.

Контракт из отчёта по инфраструктуре соблюдён: порт 8000 на 0.0.0.0, `GET /health` → 200, логи в stdout.

Необязательные переменные описаны в `.env.example` (у всех есть значения по умолчанию).
Самая полезная для разработки — `DEV_AUTH_ENABLED=true`: вход без Google через `POST /api/v1/auth/dev`,
пока мобильная команда не создала OAuth-клиент. На проде держать `false`.

## Структура

```
app/
  main.py              запуск, /health, подключение роутеров
  config.py            переменные окружения и загрузка параметров анализа
  analysis_params.json пороги и эталоны алгоритма (PLACEHOLDER - ждём от заказчика)
  db.py                пул, миграции, проверка схемы, заведение устройств эмулятора
  parsers.py           разбор топиков и пакетов (13 байт датчика, JSON реле)
  mqtt.py              подписка, переподключение, публикация команд
  analysis.py          Analysis Engine: этапы 1-4, правила тревоги, состояния Дома
  engine.py            Ingest + Command Service + уведомления: связывает всё вместе
  ws.py                рассылка событий по WebSocket
  security.py          JWT и проверка Google id_token
  api/                 REST и WebSocket
tests/                 юнит-тесты (pytest)
tools/e2e_check.py     сквозная проверка на живых БД и брокере
```

## MQTT

| Топик | Что делает бэкенд |
|---|---|
| `device/{id}/telemetry` | 13 байт `<fffB`: влажность, температура, VOC, радар. Пишет в `telemetry_history` (val_1=темп., val_2=влажн., val_3=VOC, val_4=радар), отдаёт в анализ |
| `relay/{id}/power` | JSON `{"voltage","current","state"}` (как у эмулятора; также `"218.9,8.14,1"`). Пишет val_1=U, val_2=I, val_3=NULL, val_4=статус |
| `relay/{id}/command` | Публикует `"0"` по тревоге или `"0"/"1"` по команде из приложения (QoS 1) |

Порядок байт датчика — `SENSOR_BYTE_ORDER` (по умолчанию little-endian).
Формат реле не описан в ТЗ, весь разбор — одна функция `parse_relay_packet` в `app/parsers.py`.
Пакеты от устройств, которых нет в `devices`, пропускаются (FK); ID эмулятора заводятся при старте (`SEED_DEVICES`).

## REST API

Все запросы, кроме `/health`, `/auth/*` и `/firmware/*`, требуют `Authorization: Bearer <JWT>`.
Интерактивная документация: `https://<домен>/docs`.

| Метод и путь | Назначение |
|---|---|
| `GET /health` | `{"status":"ok","db":true,"mqtt":true,"stats":{...}}` |
| `POST /api/v1/auth/google` | `{"id_token": "..."}` → `{"access_token","token_type","expires_in","user","is_new_user"}` |
| `GET /api/v1/auth/me` | текущий пользователь |
| `POST /api/v1/homes` | `{"name","sensor_id","relay_id", "sensor_mac"?, "relay_mac"?, "sensor_firmware"?, "relay_firmware"?}` → **201** + Дом. 409, если устройство уже чьё-то или другого типа |
| `GET /api/v1/homes` | Дома пользователя (с состоянием) |
| `GET /api/v1/homes/{id}` | один Дом |
| `DELETE /api/v1/homes/{id}` | удалить Дом, устройства освобождаются (история остаётся) |
| `GET /api/v1/homes/{id}/status` | состояние (Idle/Cooking/Classified/Normal/Alarm/RelayOff), вердикт, процесс, последние показания |
| `POST /api/v1/homes/{id}/relay/command` | `{"command": 0 \| 1}`. 1 после тревоги = ручной сброс → Idle. 503, если брокер недоступен |
| `PUT /api/v1/homes/{id}/protection` | `{"enabled": false}` — анализ и уведомления идут, но команда 0 автоматически не шлётся |
| `GET /api/v1/homes/{id}/alarms?limit=50` | история тревог |
| `GET /api/v1/homes/{id}/commands?limit=50` | журнал команд реле (auto/manual) |
| `GET /api/v1/analytics` | точки для графиков, см. ниже |
| `GET /api/v1/firmware/check?device_id=&current_version=` | заглушка: `update_available: false` |
| `GET /api/v1/firmware/download?device_id=&version=` | заглушка: пустой `.bin`, заголовок `X-Firmware-Stub: true` |
| `WS /api/v1/ws?token=<JWT>` | события в реальном времени (красный экран) |

### Аналитика

`GET /api/v1/analytics?home_id=1&device_type=sensor&from=2026-10-29T10:00:00Z&to=2026-10-29T11:00:00Z&step=60`

- `home_id` + `device_type` (`sensor` по умолчанию | `relay`) или `device_id`;
- `from`/`to` — ISO 8601, по умолчанию последний час, максимум 31 день;
- `step` — шаг в секундах; если не задан, подбирается под `points` (300 по умолчанию, максимум 2000).

```json
{"device_id":"emu-sensor-1","device_type":"sensor","home_id":1,
 "from":"...Z","to":"...Z","step_s":60,"fields":["temperature","humidity","voc","radar"],
 "points":[{"t":"2026-10-29T10:00:00Z","n":8,"temperature":24.1,"humidity":45.0,"voc":120.0,"radar":1}]}
```
Значения — среднее за интервал; `radar` и `state` реле — максимум (было ли движение / было ли включено). `n` — сколько пакетов в интервале.

### WebSocket

Сразу после подключения: `{"type":"snapshot","homes":[...статусы...]}`. Дальше:

- `{"type":"alarm","home_id":1,"reason":"radar_critical|dangerous_dynamics|process_thresholds|sensor_lost","details":{...},"command_sent":true,"at":"...Z"}` — **красный экран**;
- `{"type":"state", ...статус Дома...}` — смена состояния;
- `{"type":"sensor_offline|relay_offline|sensor_online|relay_online","home_id":1,...}` — обрыв/восстановление связи.

Клиент может слать `ping` → `pong`. Если приложение в фоне, состояние всегда можно забрать `GET /homes/{id}/status`.

## Алгоритм анализа

См. `app/analysis.py`, параметры — `app/analysis_params.json`.

1. **Препроцессинг**: K_HT = Hum/Temp, K_VT = VOC/Temp, K_VH = VOC/Hum, R = радар.
2. **Производные**: (K[n+1] − K[n]) / dt (ед./мин), событие весом T = 2 с. Отличие от опорной > 5% → отдельное событие, иначе усреднение с весами и T суммируется. Стабильным коэффициент считается при T ≥ 4 с.
3. **Классификация**: сравнение с эталонами Варки/Жарки ±13%; неоднозначно → пересечение HT ∩ VT ∩ VH.
4. **Вердикт**: матрица порогов процесса, правило «И» с мягким допуском → НОРМА или ТРЕВОГА.

ТРЕВОГА также, если радар прислал «критическое» значение, или плита включена (просадка напряжения), температура/VOC растут, а движения нет дольше `no_motion_s`.
При тревоге: команда `0` в реле (если защита включена), запись в `alarms`, событие в WebSocket, состояние `RelayOff` до ручного сброса командой `1`. Если реле не выключилось, команда повторяется не чаще раза в 30 с. Тревога сохраняется в `home_state` и переживает перезапуск сервиса.

**Важно:** эталоны, пороги и коды радара в ТЗ не заданы, в `analysis_params.json` стоят заглушки (PLACEHOLDER). Когда заказчик пришлёт значения, менять нужно только этот файл.

## База данных

Миграции применяются при старте (`schema_migrations`, advisory lock). На уже созданной схеме из `infra/db/init/001_schema.sql` они ничего не ломают (`IF NOT EXISTS`). Добавлено:

- `devices.home_id` может быть NULL (устройство заведено заранее или Дом удалён);
- `home_state` — текущее состояние Дома и флаг защиты;
- `alarms` — история тревог;
- `relay_commands` — журнал команд;
- уникальный индекс `users.google_id`.

При старте сервис проверяет, что в таблицах есть все колонки из раздела 5 ТЗ, и падает с понятной ошибкой, если нет.

## Тесты

```bash
pip install -r requirements-dev.txt
pytest -q                                   # 43 юнит-теста
# сквозная проверка на локальной связке (docker compose), бэкенд с DEV_AUTH_ENABLED=true
python tools/e2e_check.py --api https://localhost --mqtt-host localhost --mqtt-port 1883 \
    --mqtt-user backend --mqtt-password <пароль>
```
