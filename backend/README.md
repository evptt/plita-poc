# Бэкенд PoC «Контроль плиты» (Java)

Сервис на место заглушки в `infra/backend/`. Java 21 + Spring Boot 3.3, сборка Maven.
Используются PostgreSQL (JdbcTemplate + HikariCP + Flyway), MQTT (Eclipse Paho), JWT (Nimbus JOSE).

## Как поставить

1. Заменить содержимое `infra/backend/` содержимым этой папки.
2. В `docker-compose.yml` ничего менять не нужно. Используются переменные, которые уже приходят в контейнер:
   `DATABASE_URL`, `MQTT_HOST`, `MQTT_PORT`, `MQTT_USER`, `MQTT_PASSWORD`, `JWT_SECRET`, `GOOGLE_CLIENT_ID`.
3. Локально: `docker compose up -d --build`, затем `curl -k https://localhost/health`.
4. Дальше обычный порядок: ветка → pull request → мерж в `main` → автодеплой сам проверит `/health`.

Контракт из отчёта по инфраструктуре соблюдён: порт 8000 на 0.0.0.0, `GET /health` отвечает 200, логи идут в stdout.

**Память.** В отчёте предупреждали, что на Java/Spring сервер с 2 ГБ будет тесным. В `Dockerfile` память JVM ограничена: heap 192 МБ, SerialGC, только C1-компилятор. Ожидаемый расход — примерно 250–300 МБ. Сейчас на сервере свободно 1,3 ГБ и есть swap, так что должно влезть. Админу о переходе на Java сказать всё равно нужно.

**Время сборки.** Docker собирает jar внутри образа (multi-stage). Первая выкатка дольше, чем раньше: Maven скачивает зависимости, это 2–4 минуты. Дальше слой с зависимостями берётся из кеша Docker, пока `pom.xml` не менялся.

Необязательные переменные:

| Переменная | По умолчанию | Зачем |
|---|---|---|
| `DEV_AUTH_ENABLED` | `false` | вход без Google через `POST /api/v1/auth/dev` на время разработки |
| `JWT_TTL_HOURS` | `720` | срок жизни JWT |
| `SENSOR_BYTE_ORDER` | `little` | порядок байт в пакете датчика (`little` или `big`) |
| `SEED_DEVICES` | `emu-sensor-1:sensor,emu-relay-1:relay` | устройства, которые заводятся при старте |
| `FIRMWARE_LATEST_VERSION` | `0.0.0` | что отдаёт заглушка OTA |
| `ANALYSIS_PARAMS_PATH` | (файл внутри jar) | свой файл параметров анализа |
| `JAVA_OPTS` | см. Dockerfile | настройки памяти JVM |

Новую переменную нужно добавить в `docker-compose.yml` и `.env.example` в `infra/` и попросить админа прописать её в `.env`.

## Сборка без Docker

```bash
mvn test               # 35 юнит-тестов
mvn package            # target/plita-backend.jar
DATABASE_URL=postgresql://plita:plita@localhost:5432/plita MQTT_HOST=localhost DEV_AUTH_ENABLED=true \
  java -jar target/plita-backend.jar
```

## Структура

```
src/main/java/com/plita/backend/
  PlitaApplication.java       запуск
  analysis/                   Analysis Engine, чистая Java без Spring:
    AnalysisParams              параметры алгоритма
    Algorithm                   этапы 1, 3, 4 и наклон для опасной динамики
    SlopeStabilizer             этап 2: производные и стабилизация
    HomeAnalyzer                состояния Дома и правила тревоги
    Model, Decision             типы: состояния, вердикты, отсчёты, решения
  ingest/
    Packets                     разбор топиков и 13-байтового пакета датчика
    RelayPacketParser           разбор пакета реле (JSON эмулятора / текст)
    MqttService                 подписка, переподключение, публикация команд
  engine/Engine.java          Ingest + Command Service + уведомления
  security/                   JWT и проверка Google id_token
  api/                        REST-контроллеры, ошибки, текущий пользователь
  ws/                         WebSocket /api/v1/ws
  config/                     переменные окружения, бины, порядок старта
src/main/resources/
  application.yml
  analysis-params.json        пороги и эталоны (PLACEHOLDER: ждём от заказчика)
  db/migration/V1__..., V2__  миграции Flyway
tools/e2e_check.py            сквозная проверка на живых БД и брокере (Python)
```

## MQTT

| Топик | Что делает бэкенд |
|---|---|
| `device/{id}/telemetry` | Принимает 13 байт little-endian: влажность, температура, VOC (float32), радар (uint8). Пишет в `telemetry_history`: val_1 = температура, val_2 = влажность, val_3 = VOC, val_4 = радар. Передаёт в анализ |
| `relay/{id}/power` | Принимает JSON `{"voltage","current","state"}`, как у эмулятора, или текст `"218.9,8.14,1"`. Пишет val_1 = U, val_2 = I, val_3 = NULL, val_4 = статус |
| `relay/{id}/command` | Публикует `"0"` по тревоге или `"0"`/`"1"` по команде из приложения (QoS 1) |

Формат пакета реле в ТЗ не описан, поэтому весь его разбор собран в одном классе — `RelayPacketParser`.

Пакеты от устройств, которых нет в таблице `devices`, пропускаются. ID эмулятора заводятся при старте.

## REST API

JSON в snake_case, ошибки приходят как `{"detail": "..."}`. Все запросы требуют `Authorization: Bearer <JWT>`, кроме `/health`, `/api/v1/auth/google`, `/api/v1/auth/dev` и `/firmware/*`.

Интерактивная документация: `https://<домен>/docs`, схема — `/openapi.json`.

| Метод и путь | Назначение |
|---|---|
| `GET /health` | `{"status":"ok","db":true,"mqtt":true,"stats":{...}}` |
| `POST /api/v1/auth/google` | `{"id_token"}` → `{"access_token","token_type","expires_in","user","is_new_user"}`. Если `GOOGLE_CLIENT_ID` не задан — 503 |
| `GET /api/v1/auth/me` | текущий пользователь |
| `POST /api/v1/homes` | `{"name","sensor_id","relay_id", "sensor_mac"?, "relay_mac"?, "sensor_firmware"?, "relay_firmware"?}` → **201**. 409, если устройство чужое, уже привязано или другого типа |
| `GET /api/v1/homes`, `GET /api/v1/homes/{id}` | Дома пользователя |
| `DELETE /api/v1/homes/{id}` | 204; устройства отвязываются, история остаётся |
| `GET /api/v1/homes/{id}/status` | состояние (Idle/Cooking/Classified/Normal/Alarm/RelayOff), вердикт, процесс, последние показания |
| `POST /api/v1/homes/{id}/relay/command` | `{"command": 0 \| 1}`. Команда 1 после тревоги снимает тревогу (состояние Idle). 503, если брокер недоступен |
| `PUT /api/v1/homes/{id}/protection` | `{"enabled": false}`: анализ и уведомления продолжаются, но команда 0 автоматически не уходит |
| `GET /api/v1/homes/{id}/alarms?limit=50` | история тревог |
| `GET /api/v1/homes/{id}/commands?limit=50` | журнал команд реле (`auto` и `manual`) |
| `GET /api/v1/analytics` | точки для графиков |
| `GET /api/v1/firmware/check`, `/download` | заглушки OTA |
| `WS /api/v1/ws?token=<JWT>` | события в реальном времени («красный экран») |

### Аналитика

Пример: `GET /api/v1/analytics?home_id=1&device_type=sensor&from=2026-10-29T10:00:00Z&to=2026-10-29T11:00:00Z&step=60`

- Устройство задаётся парой `home_id` + `device_type` (`sensor` по умолчанию или `relay`) либо напрямую через `device_id`.
- Период задаётся `from` и `to`. По умолчанию — последний час, максимум — 31 день.
- `step` — шаг в секундах. Если его нет, шаг подбирается под `points` (по умолчанию 300 точек, максимум 2000).
- В каждой точке — среднее за интервал. Для `radar` и `state` берётся максимум: было ли движение и было ли включено.

### WebSocket

При подключении приходит `{"type":"snapshot","homes":[...]}`. Дальше приходят события:

- `alarm` — красный экран: `{"type":"alarm","home_id":1,"reason":"radar_critical|dangerous_dynamics|process_thresholds|sensor_lost","details":{...},"command_sent":true,"at":"...Z"}`;
- `state` — смена состояния Дома;
- `sensor_offline` / `relay_offline` / `sensor_online` / `relay_online` — потеря и восстановление связи.

На `ping` сервер отвечает `pong`.

## Алгоритм анализа

Код — в пакете `analysis`. Он написан без Spring и покрыт тестами.

1. **Препроцессинг.** K_HT = Hum/Temp, K_VT = VOC/Temp, K_VH = VOC/Hum.
2. **Производные.** (K[n+1] − K[n]) / dt в ед./мин, вес события T = 2 с.
   - Если новая производная отличается от опорной больше чем на 5%, это отдельное событие.
   - Иначе производные усредняются, T суммируется.
   - Коэффициент стабилен, когда T ≥ 4 с.
3. **Классификация.** Эталоны Варки и Жарки с допуском ±13%. Если класс неоднозначен — пересечение HT ∩ VT ∩ VH.
4. **Вердикт.** Матрица порогов процесса, правило «И» с мягким допуском.

ТРЕВОГА выносится ещё в двух случаях:
- радар прислал «критическое» значение;
- плита включена (просадка напряжения), температура или VOC растут, а движения нет дольше `no_motion_s`.

Что происходит при тревоге:
- реле получает команду `0`, если защита включена;
- тревога записывается в `alarms`;
- в WebSocket уходит событие;
- Дом остаётся в состоянии `RelayOff`, пока пользователь вручную не отправит `1`.

Если реле не выключилось, команда повторяется, но не чаще раза в 30 с. Тревога сохраняется после перезапуска сервиса.

**Важно:** эталоны, пороги и коды радара в ТЗ не заданы. В `analysis-params.json` стоят заглушки. Когда заказчик пришлёт значения, менять нужно только этот файл. Тест `AnalysisParamsLoaderTest` следит, чтобы файл совпадал с `AnalysisParams.defaults()`.

## База данных

Миграции применяет Flyway при старте.

На базе, где таблицы уже созданы `infra/db/init/001_schema.sql`, Flyway ставит baseline 0 и применяет V1 и V2. V1 написана через `IF NOT EXISTS`, поэтому существующие таблицы не трогает.

V2 добавляет:
- `devices.home_id` может быть NULL;
- таблицу `home_state` — состояние Дома и флаг защиты;
- таблицу `alarms`;
- таблицу `relay_commands`;
- уникальный индекс на `users.google_id`.

Драйвер работает с `stringtype=unspecified`. Это нужно, потому что в `001_schema.sql` колонка `devices.type` может быть ENUM, а строки без этой настройки туда не вставятся. При старте сервис проверяет, что все колонки из раздела 5 ТЗ на месте.

## Тесты

```bash
mvn test        # 35 юнит-тестов: алгоритм, разбор пакетов, настройки, файл параметров
# сквозная проверка, 32 пункта. Бэкенд запущен с DEV_AUTH_ENABLED=true
pip install -r tools/requirements.txt
python tools/e2e_check.py --api https://localhost --mqtt-host localhost --mqtt-port 1883 \
    --mqtt-user backend --mqtt-password <пароль>
```
