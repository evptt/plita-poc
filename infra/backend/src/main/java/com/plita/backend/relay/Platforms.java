package com.plita.backend.relay;

/**
 * Через что бэкенд общается с устройством (колонка devices.platform).
 * <ul>
 *   <li>{@code mqtt} - наш брокер: датчик и эмулятор реле (relay/{id}/power и relay/{id}/command);</li>
 *   <li>{@code tuya} - автомат Tongou на заводской прошивке Tuya: данные и команды через Tuya Cloud OpenAPI.</li>
 * </ul>
 */
public final class Platforms {

    public static final String MQTT = "mqtt";
    public static final String TUYA = "tuya";

    private Platforms() {
    }

    public static boolean isValid(String p) {
        return MQTT.equals(p) || TUYA.equals(p);
    }
}
