# ЗАГЛУШКА. Бэкенд-команда заменяет эту папку своим кодом и Dockerfile.
# Требование к их сервису: слушать порт 8000 и отвечать на GET /health.
FROM python:3.12-slim
WORKDIR /app
COPY app.py .
EXPOSE 8000
CMD ["python", "-u", "app.py"]
