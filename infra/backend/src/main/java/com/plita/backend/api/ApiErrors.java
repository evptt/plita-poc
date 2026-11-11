package com.plita.backend.api;

import com.plita.backend.ingest.MqttService.MqttUnavailableException;
import com.plita.backend.security.AuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Единый формат ошибок: {"detail": "..."}.
 * Ошибки входных данных -> 422 (как договорено с мобильной командой), нет токена -> 401.
 */
@RestControllerAdvice
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    /** Короткий способ вернуть ошибку из контроллера. */
    public static ResponseStatusException error(HttpStatus status, String detail) {
        return new ResponseStatusException(status, detail);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail == null ? status.getReasonPhrase() : detail));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        return body(HttpStatus.valueOf(e.getStatusCode().value()), e.getReason());
    }

    @ExceptionHandler(AuthException.Unavailable.class)
    public ResponseEntity<Map<String, Object>> authUnavailable(AuthException.Unavailable e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> auth(AuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(MqttUnavailableException.class)
    public ResponseEntity<Map<String, Object>> mqtt(MqttUnavailableException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "реле недоступно (MQTT-брокер или Tuya Cloud), команда не отправлена");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getAllErrors().stream()
                .map(err -> err instanceof FieldError fe ? fe.getField() + ": " + fe.getDefaultMessage()
                        : err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, HandlerMethodValidationException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badInput(Exception e) {
        String detail = e instanceof HttpMessageNotReadableException ? "некорректное тело запроса" : e.getMessage();
        return body(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "Not Found");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("Необработанная ошибка", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "внутренняя ошибка сервера");
    }
}
