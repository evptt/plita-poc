#!/usr/bin/env bash
# Первичная настройка чистого сервера Ubuntu 24.04. Запускать ОДИН раз от root:
#   bash server-setup.sh
set -euo pipefail

echo "==> Обновляем систему"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get -y upgrade
apt-get -y install ca-certificates curl ufw fail2ban unattended-upgrades

echo "==> Ставим Docker"
if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker

echo "==> Создаём пользователя deploy (работаем под ним, а не под root)"
if ! id deploy >/dev/null 2>&1; then
  adduser --disabled-password --gecos "" deploy
fi
usermod -aG docker,sudo deploy
echo "deploy ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/deploy
if [ -s /root/.ssh/authorized_keys ]; then
  mkdir -p /home/deploy/.ssh
  cp /root/.ssh/authorized_keys /home/deploy/.ssh/authorized_keys
  chown -R deploy:deploy /home/deploy/.ssh
  chmod 700 /home/deploy/.ssh && chmod 600 /home/deploy/.ssh/authorized_keys
fi

echo "==> Файрвол: открываем только SSH, HTTP(S) и MQTT"
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 1883/tcp
ufw --force enable

echo "==> Swap 2 ГБ (страховка от нехватки памяти)"
if ! swapon --show | grep -q .; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo "/swapfile none swap sw 0 0" >> /etc/fstab
fi

echo "==> Защита от перебора паролей и автообновления безопасности"
systemctl enable --now fail2ban
dpkg-reconfigure -f noninteractive unattended-upgrades

# Вход по паролю выключаем, только если SSH-ключ уже настроен — иначе потеряешь доступ
if [ -s /root/.ssh/authorized_keys ]; then
  echo "==> Отключаем вход по паролю (ключ найден)"
  sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
  rm -f /etc/ssh/sshd_config.d/50-cloud-init.conf
  systemctl restart ssh
else
  echo "!! SSH-ключ не найден — вход по паролю оставлен. Добавь ключ и перезапусти скрипт."
fi

echo "==> Готово. Дальше заходи как: ssh deploy@<IP сервера>"
