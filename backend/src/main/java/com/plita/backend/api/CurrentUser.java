package com.plita.backend.api;

import com.plita.backend.security.AuthException;
import com.plita.backend.security.JwtService;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;
import java.util.Map;

/**
 * Текущий пользователь. Достаточно объявить параметр типа CurrentUser в методе контроллера -
 * запрос без валидного «Authorization: Bearer &lt;JWT&gt;» получит 401.
 */
public record CurrentUser(long id, String googleId, String email, String name) {

    @Component
    public static class Resolver implements HandlerMethodArgumentResolver {

        private final JwtService jwt;
        private final JdbcTemplate jdbc;

        public Resolver(JwtService jwt, JdbcTemplate jdbc) {
            this.jwt = jwt;
            this.jdbc = jdbc;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == CurrentUser.class;
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                      NativeWebRequest request, WebDataBinderFactory binderFactory) {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new AuthException("нужен заголовок Authorization: Bearer <JWT>");
            }
            long userId = jwt.verify(header.substring(7).trim());
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, google_id, email, name FROM users WHERE id = ?", userId);
            if (rows.isEmpty()) {
                throw new AuthException("пользователь не найден");
            }
            Map<String, Object> r = rows.get(0);
            return new CurrentUser(((Number) r.get("id")).longValue(), (String) r.get("google_id"),
                    (String) r.get("email"), (String) r.get("name"));
        }
    }
}
