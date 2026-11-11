package com.plita.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plita.backend.analysis.AnalysisParams;
import com.plita.backend.security.GoogleTokenVerifier;
import com.plita.backend.security.JwtService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public AppSettings appSettings() {
        return AppSettings.fromEnv(System.getenv());
    }

    @Bean
    public AnalysisParams analysisParams(ObjectMapper mapper, AppSettings settings) throws IOException {
        String path = settings.analysisParamsPath();
        log.info("Параметры анализа: {}", path.isBlank() ? "classpath:" + AnalysisParamsLoader.CLASSPATH_RESOURCE : path);
        return AnalysisParamsLoader.load(mapper, path);
    }

    /** DATABASE_URL (postgresql://user:pass@host:port/db) -> пул HikariCP. Ждём БД до 60 с. */
    @Bean
    public DataSource dataSource(AppSettings settings) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(settings.db().jdbcUrl());
        if (settings.db().user() != null) {
            c.setUsername(settings.db().user());
        }
        if (settings.db().password() != null) {
            c.setPassword(settings.db().password());
        }
        c.setMaximumPoolSize(10);
        c.setMinimumIdle(1);
        c.setConnectionTimeout(10_000);
        c.setInitializationFailTimeout(60_000);
        c.setPoolName("plita-db");
        // строки передаются без типа: Postgres сам приводит их к типу колонки
        // (нужно, если devices.type в 001_schema.sql объявлен как ENUM)
        c.addDataSourceProperty("stringtype", "unspecified");
        return new HikariDataSource(c);
    }

    @Bean
    public JwtService jwtService(AppSettings settings) {
        return new JwtService(settings.jwtSecret(), settings.jwtTtlHours());
    }

    @Bean
    public GoogleTokenVerifier googleTokenVerifier(AppSettings settings) {
        return new GoogleTokenVerifier(settings.googleClientIds());
    }
}
