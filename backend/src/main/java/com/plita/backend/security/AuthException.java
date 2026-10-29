package com.plita.backend.security;

/** Неверный токен -> 401. */
public class AuthException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AuthException(String message) {
        super(message);
    }

    /** Проблема на стороне сервера (нет GOOGLE_CLIENT_ID или Google недоступен) -> 503. */
    public static class Unavailable extends AuthException {
        private static final long serialVersionUID = 1L;

        public Unavailable(String message) {
            super(message);
        }
    }
}
