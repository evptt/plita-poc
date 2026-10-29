package com.plita.backend.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.plita.backend.config.AppSettings;
import com.plita.backend.security.GoogleTokenVerifier;
import com.plita.backend.security.JwtService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Авторизация (рис. 3): Google id_token -> внутренний JWT. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record GoogleLogin(@NotBlank @Size(min = 10) String idToken) {
    }

    public record DevLogin(@NotBlank @Size(min = 3, max = 200) String email, @Size(max = 200) String name) {
    }

    public record UserOut(long id, String email, String name) {
    }

    public record TokenOut(String accessToken, String tokenType, long expiresIn, UserOut user,
                           @JsonProperty("is_new_user") boolean isNewUser) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JwtService jwt;
    private final GoogleTokenVerifier google;
    private final AppSettings settings;

    public AuthController(JdbcTemplate jdbc, TransactionTemplate tx, JwtService jwt, GoogleTokenVerifier google,
                          AppSettings settings) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.jwt = jwt;
        this.google = google;
        this.settings = settings;
    }

    @PostMapping("/google")
    public TokenOut loginGoogle(@Valid @RequestBody GoogleLogin body) {
        GoogleTokenVerifier.GoogleUser u = google.verify(body.idToken());
        return login(u.googleId(), u.email(), u.name());
    }

    /** Вход без Google - только для разработки, пока нет OAuth-клиента. Включается DEV_AUTH_ENABLED=true. */
    @Hidden
    @PostMapping("/dev")
    public TokenOut loginDev(@Valid @RequestBody DevLogin body) {
        if (!settings.devAuthEnabled()) {
            throw ApiErrors.error(HttpStatus.NOT_FOUND, "Not Found");
        }
        return login("dev:" + body.email().toLowerCase(), body.email(), body.name());
    }

    @GetMapping("/me")
    public UserOut me(CurrentUser user) {
        return new UserOut(user.id(), user.email(), user.name());
    }

    private TokenOut login(String googleId, String email, String name) {
        record Result(long id, boolean isNew) {
        }
        Result r = tx.execute(status -> {
            List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE google_id = ? ORDER BY id LIMIT 1",
                    Long.class, googleId);
            if (ids.isEmpty()) {
                Long id = jdbc.queryForObject("INSERT INTO users (google_id, email, name, created_at) "
                        + "VALUES (?, ?, ?, now()) RETURNING id", Long.class, googleId, email, name);
                return new Result(id, true);
            }
            jdbc.update("UPDATE users SET email = COALESCE(?, email), name = COALESCE(?, name) WHERE id = ?",
                    email, name, ids.get(0));
            return new Result(ids.get(0), false);
        });
        JwtService.IssuedToken t = jwt.issue(r.id());
        return new TokenOut(t.token(), "Bearer", t.expiresInSeconds(), new UserOut(r.id(), email, name), r.isNew());
    }
}
