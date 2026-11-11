package com.plita.backend.relay;

/** Ошибка Tuya OpenAPI: code и msg из ответа Tuya или сетевая ошибка (code = -1). */
public class TuyaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int code;

    public TuyaException(int code, String message) {
        super(message);
        this.code = code;
    }

    public TuyaException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }

    public int code() {
        return code;
    }

    /** Сеть или сервер Tuya недоступны, а не ошибка в запросе. */
    public boolean isNetwork() {
        return code == -1;
    }
}
