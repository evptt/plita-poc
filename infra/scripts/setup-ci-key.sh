#!/usr/bin/env bash
# Одноразовая настройка автодеплоя: даёт GitHub Actions доступ к серверу.
# Создаёт новый SSH-ключ, разрешает его пользователю deploy на сервере,
# кладёт приватную часть в секреты репозитория и удаляет локальную копию.
# Запуск (из папки D:\Studying\startup):  bash infra/scripts/setup-ci-key.sh
# Отозвать доступ: удалить строку "github-actions-..." из ~/.ssh/authorized_keys на сервере.
set -euo pipefail
SERVER=deploy@177.177.223.116
REPO=evptt/plita-poc

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

ssh-keygen -t ed25519 -N "" -C "github-actions-$REPO" -f "$TMP/key" -q
ssh -i ~/.ssh/plita_deploy -o IdentitiesOnly=yes "$SERVER" 'cat >> ~/.ssh/authorized_keys' < "$TMP/key.pub"
ssh -i "$TMP/key" -o IdentitiesOnly=yes -o BatchMode=yes "$SERVER" true
gh secret set DEPLOY_SSH_KEY --repo "$REPO" < "$TMP/key"
echo "Готово: GitHub Actions может деплоить на сервер, локальная копия ключа удалена."
