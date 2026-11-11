package com.plita.backend.push;

import com.plita.backend.api.ApiErrors;
import com.plita.backend.api.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FCM-токены приложения для push «красного экрана».
 *
 * <ul>
 *   <li>POST /api/v1/push/tokens {"token", "platform"?} - после входа и при каждом обновлении токена FCM;</li>
 *   <li>DELETE /api/v1/push/tokens/{token} - при выходе из аккаунта;</li>
 *   <li>POST /api/v1/push/test - проверочный push на свои устройства.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/push")
public class PushController {

    public record TokenIn(@NotBlank @Size(max = 4096) String token,
                          @Pattern(regexp = "^(android|ios|web)$") String platform) {
    }

    private final JdbcTemplate jdbc;
    private final PushService push;

    public PushController(JdbcTemplate jdbc, PushService push) {
        this.jdbc = jdbc;
        this.push = push;
    }

    /** Токен привязывается к текущему пользователю (если раньше был у другого - переезжает). */
    @PostMapping("/tokens")
    public ResponseEntity<Void> register(@Valid @RequestBody TokenIn body, CurrentUser user) {
        jdbc.update("""
                INSERT INTO push_tokens (token, user_id, platform) VALUES (?, ?, ?)
                ON CONFLICT (token) DO UPDATE SET user_id = EXCLUDED.user_id, platform = EXCLUDED.platform,
                    updated_at = now()""", body.token(), user.id(), body.platform());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/tokens/{token}")
    public ResponseEntity<Void> unregister(@PathVariable String token, CurrentUser user) {
        jdbc.update("DELETE FROM push_tokens WHERE token = ? AND user_id = ?", token, user.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    public Map<String, Object> test(CurrentUser user) {
        if (!push.enabled()) {
            throw ApiErrors.error(HttpStatus.SERVICE_UNAVAILABLE, "push не настроен: нет FIREBASE_SERVICE_ACCOUNT на сервере");
        }
        PushService.Result r;
        try {
            r = push.sendTest(user.id());
        } catch (IllegalStateException e) {
            throw ApiErrors.error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", r.sent());
        out.put("failed", r.failed());
        out.put("removed_tokens", r.removed());
        return out;
    }
}
