package com.plita.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Бэкенд «Контроль плиты» (PoC).
 *
 * <p>Контракт сервиса из отчёта по инфраструктуре: слушать 0.0.0.0:8000 (HTTPS делает Caddy),
 * GET /health -> 200 (по нему автодеплой проверяет выкатку), логи в stdout.
 */
@SpringBootApplication
@EnableScheduling
public class PlitaApplication {

    public static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        SpringApplication.run(PlitaApplication.class, args);
    }
}
