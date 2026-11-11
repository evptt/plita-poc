#!/usr/bin/env bash
# Создаёт .env со случайными паролями. Запуск из папки infra:  bash scripts/gen-env.sh
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  echo ".env уже существует — не трогаю (иначе пароли разойдутся с базой)."; exit 1
fi
IP=$(curl -fsS https://api.ipify.org || hostname -I | awk '{print $1}')
pw() { openssl rand -hex 24; }
cat > .env <<EOF
DOMAIN=${IP//./-}.sslip.io
COMPOSE_PROFILES=emulator
POSTGRES_DB=plita
POSTGRES_USER=plita
POSTGRES_PASSWORD=$(pw)
MQTT_BACKEND_PASSWORD=$(pw)
MQTT_DEVICE_PASSWORD=$(pw)
JWT_SECRET=$(openssl rand -hex 32)
GOOGLE_CLIENT_ID=
DEV_AUTH_ENABLED=false
TUYA_ACCESS_ID=
TUYA_ACCESS_SECRET=
TUYA_ENDPOINT=https://openapi.tuyaeu.com
TUYA_POLL_INTERVAL_S=120
FIREBASE_SERVICE_ACCOUNT=
FIREBASE_ANDROID_CHANNEL_ID=
EOF
chmod 600 .env
echo "Готово. API будет доступен по адресу: https://${IP//./-}.sslip.io"
