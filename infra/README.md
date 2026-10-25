# Инфраструктура PoC «Контроль плиты»

## Что тут лежит и зачем

```
Датчик / Реле ──MQTT:1883──► [mosquitto] ◄──► [backend] ──► [db: PostgreSQL]
Мобилка ──────HTTPS:443────► [caddy] ─────────► [backend]
```

| Папка/файл | Что это |
|---|---|
| `docker-compose.yml` | Главный файл: описывает все сервисы. Одна команда поднимает всё |
| `mosquitto/` | MQTT-брокер: с логинами и правами на топики |
| `db/init/001_schema.sql` | Таблицы из ТЗ (Users, Homes, Devices, Telemetry_History) |
| `caddy/` | HTTPS-прокси, сертификат получает сам |
| `backend/` | **Заглушка.** Бэкендеры кладут сюда свой код (порт 8000, `GET /health`) |
| `emulator/` | Фейковые датчик и реле, шлют данные, как настоящие |
| `scripts/` | Настройка сервера, генерация паролей, бэкапы |

---

## Шаг 1. SSH-ключ (на своём компьютере, один раз)

PowerShell:
```
ssh-keygen -t ed25519
```
Жми Enter на все вопросы. Публичный ключ покажет:
```
type $env:USERPROFILE\.ssh\id_ed25519.pub
```
Строку `ssh-ed25519 AAAA...` скопируй — она понадобится в Timeweb.
Файл `id_ed25519` (без `.pub`) — секретный, никому не отдавать.

## Шаг 2. Покупка сервера в Timeweb Cloud

Облачные серверы → Создать:
- **ОС:** Ubuntu 24.04
- **Конфигурация:** 2 vCPU / 4 ГБ RAM / 40–50 ГБ NVMe (для PoC хватит с запасом; на 2 ГБ тоже заведётся)
- **Регион:** любой ближайший (Москва / Санкт-Петербург)
- **SSH-ключ:** добавить ключ из шага 1
- Публичный IPv4 — нужен (обычно включён)

После создания запиши **IP-адрес** сервера.

## Шаг 3. Первичная настройка сервера

С компа (PowerShell, из папки `D:\Studying\startup`):
```
scp -r infra root@IP_СЕРВЕРА:/root/
ssh root@IP_СЕРВЕРА
```
Уже на сервере:
```
bash /root/infra/scripts/server-setup.sh
mv /root/infra /home/deploy/infra && chown -R deploy:deploy /home/deploy/infra
exit
```
Скрипт: обновит систему, поставит Docker, создаст пользователя `deploy`, включит файрвол
(открыты только 22, 80, 443, 1883), запретит вход по паролю.

## Шаг 4. Запуск

```
ssh deploy@IP_СЕРВЕРА
cd ~/infra
bash scripts/gen-env.sh          # создаст .env со случайными паролями
docker compose up -d --build     # поднять всё
docker compose ps                # все сервисы должны быть Up / healthy
```

## Шаг 5. Проверка

```
curl https://IP-С-ДЕФИСАМИ.sslip.io/health
```
Должен ответить `{"status": "ok", ...}`. Адрес печатал `gen-env.sh`.

Запустить эмулятор и посмотреть, как бегут данные:
```
docker compose --profile emulator up -d --build
docker compose logs -f emulator
```
Подсмотреть весь MQTT-трафик (Ctrl+C — выход):
```
source .env
docker compose exec mosquitto mosquitto_sub -u backend -P "$MQTT_BACKEND_PASSWORD" -t '#' -v
```
Вручную «выключить плиту» (эмулятор напишет `COMMAND received: 0`):
```
docker compose exec mosquitto mosquitto_pub -u backend -P "$MQTT_BACKEND_PASSWORD" -t relay/emu-relay-1/command -m 0
```
Залезть в базу:
```
docker compose exec db psql -U plita plita
```
(`\dt` — список таблиц, `\q` — выход)

## Шаг 6. Бэкапы базы (каждую ночь в 3:00)

```
crontab -e
```
Добавить строку:
```
0 3 * * * /bin/bash /home/deploy/infra/scripts/backup.sh >> /home/deploy/infra/backups/backup.log 2>&1
```

---

## Как выкатывать изменения (автодеплой)

Сервер обновляется сам: **любой пуш в ветку `main`, который меняет папку `infra/`**, запускает
GitHub Actions (`.github/workflows/deploy.yml`). Тот заливает файлы на сервер, делает
`docker compose up -d --build` и проверяет `GET /health`. Ход деплоя — вкладка **Actions** в репозитории.
Перезапустить вручную: Actions → deploy → Run workflow.

`.env` на сервере деплой не трогает — пароли меняются только руками на сервере.
Эмулятор включён через `COMPOSE_PROFILES=emulator` в `.env`; убрать значение — эмулятор перестанет запускаться.

---

## Шпаргалка

| Что нужно | Команда (из `~/infra`) |
|---|---|
| Статус | `docker compose ps` |
| Логи сервиса | `docker compose logs -f backend` |
| Перезапуск | `docker compose restart backend` |
| Обновить после изменения кода | `docker compose up -d --build` |
| Остановить всё | `docker compose down` (данные сохранятся) |
| ⚠️ Удалить всё вместе с базой | `docker compose down -v` — **не делать без бэкапа** |

## Что раздать команде

- **Бэкендерам:** строки подключения внутри docker-сети — `DATABASE_URL` и MQTT
  (`mosquitto:1883`, логин `backend`) уже передаются в контейнер `backend` через переменные окружения.
  Их сервис должен слушать порт 8000. Схема из `db/init` — стартовая, дальше миграции на них.
- **Железячникам:** брокер `IP_СЕРВЕРА:1883`, логин `device`, пароль — `MQTT_DEVICE_PASSWORD` из `.env`
  (передавать лично, не в общий чат).
- **Мобильщикам:** базовый URL API `https://IP-С-ДЕФИСАМИ.sslip.io`.

## Открытые вопросы (влияют на инфраструктуру)

1. Формат пакета `relay/power` в ТЗ не описан — эмулятор пока шлёт JSON.
2. Порядок байт в 13-байтовом пакете датчика не указан — эмулятор шлёт little-endian.
3. Канал «красного экрана» (push / WebSocket / SSE) не выбран. Если WebSocket — Caddy его пробрасывает без доп. настроек; если push — понадобится Firebase.
4. Когда появится нормальный домен — просто поменять `DOMAIN` в `.env` и `docker compose up -d`.
