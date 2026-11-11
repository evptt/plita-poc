# Бэкенд PoC «Контроль плиты» на Java 21 / Spring Boot 3.
# Контракт: порт 8000, GET /health, логи в stdout.

# ---------- сборка ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
# сначала только pom.xml: зависимости кешируются в слое и не качаются заново при каждом коммите
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------- запуск ----------
FROM eclipse-temurin:21-jre
WORKDIR /srv
RUN useradd --system --uid 10001 --no-create-home appuser
COPY --from=build /build/target/plita-backend.jar app.jar
USER appuser

EXPOSE 8000

# Сервер 1 vCPU / 2 ГБ: держим JVM в ~250-300 МБ (см. отчёт по инфраструктуре про Java/Spring).
ENV JAVA_OPTS="-Xms64m -Xmx192m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k \
 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8000 && printf "GET /health HTTP/1.0\r\n\r\n" >&3 && head -1 <&3 | grep -q " 200"'

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /srv/app.jar"]
