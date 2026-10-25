#!/usr/bin/env bash
# Бэкап базы. Хранит последние 7 дней. Ставится в cron (см. README).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p backups
set -a; source .env; set +a
docker compose exec -T db pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" | gzip > "backups/db_$(date +%F_%H%M).sql.gz"
find backups -name 'db_*.sql.gz' -mtime +7 -delete
