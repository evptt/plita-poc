package com.plita.backend.ingest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Разбор MQTT-топиков и пакета датчика (раздел 3 ТЗ). Без внешних зависимостей.
 *
 * <pre>
 * device/{device_id}/telemetry - 13 байт: float32 влажность, float32 температура,
 *                                float32 VOC, uint8 радар/вердикт датчика.
 * relay/{relay_id}/power       - см. {@link RelayPacketParser}.
 * relay/{relay_id}/command     - строка "0" (выключить плиту) или "1" (включить).
 * </pre>
 */
public final class Packets {

    public static final int SENSOR_PACKET_SIZE = 13;

    private Packets() {
    }

    public static class PacketException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PacketException(String message) {
            super(message);
        }
    }

    public enum Kind { SENSOR, RELAY }

    public record Topic(Kind kind, String deviceId) {
    }

    public record SensorReading(double humidity, double temperature, double voc, int radar) {
    }

    public record RelayReading(double voltage, double current, int state) {
    }

    public static Topic parseTopic(String topic) {
        String[] parts = topic.split("/", -1);
        if (parts.length != 3 || parts[1].isEmpty()) {
            return null;
        }
        if (parts[0].equals("device") && parts[2].equals("telemetry")) {
            return new Topic(Kind.SENSOR, parts[1]);
        }
        if (parts[0].equals("relay") && parts[2].equals("power")) {
            return new Topic(Kind.RELAY, parts[1]);
        }
        return null;
    }

    /** byteOrder: "little" (по умолчанию, родной для ESP32/STM32) или "big". */
    public static SensorReading parseSensorPacket(byte[] payload, String byteOrder) {
        if (payload.length != SENSOR_PACKET_SIZE) {
            throw new PacketException("пакет датчика должен быть " + SENSOR_PACKET_SIZE
                    + " байт, пришло " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(order(byteOrder));
        float humidity = buf.getFloat();
        float temperature = buf.getFloat();
        float voc = buf.getFloat();
        int radar = buf.get() & 0xFF;
        checkFinite("humidity", humidity);
        checkFinite("temperature", temperature);
        checkFinite("voc", voc);
        return new SensorReading(humidity, temperature, voc, radar);
    }

    /** Обратная операция - для тестов и эмулятора. */
    public static byte[] buildSensorPacket(float humidity, float temperature, float voc, int radar, String byteOrder) {
        return ByteBuffer.allocate(SENSOR_PACKET_SIZE).order(order(byteOrder))
                .putFloat(humidity).putFloat(temperature).putFloat(voc).put((byte) radar).array();
    }

    private static ByteOrder order(String byteOrder) {
        return "big".equalsIgnoreCase(byteOrder) ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    static void checkFinite(String field, double v) {
        if (!Double.isFinite(v)) {
            throw new PacketException("нечисловое значение в поле " + field);
        }
    }

    /** Статус реле: 0/1, true/false, "on"/"off". */
    public static int parseRelayState(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d == 0 || d == 1) {
                return (int) d;
            }
            throw new PacketException("статус реле должен быть 0 или 1, пришло " + value);
        }
        String text = String.valueOf(value).trim().toLowerCase();
        return switch (text) {
            case "1", "on", "true" -> 1;
            case "0", "off", "false" -> 0;
            default -> throw new PacketException("непонятный статус реле: " + value);
        };
    }

    public static double parseNumber(Object value, String field) {
        double d;
        if (value instanceof Number n) {
            d = n.doubleValue();
        } else if (value instanceof String s) {
            try {
                d = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new PacketException(field + " должно быть числом");
            }
        } else {
            throw new PacketException(field + " должно быть числом");
        }
        checkFinite(field, d);
        return d;
    }

    /** Запасной текстовый формат реле: "218.9,8.14,1" (или через ';'). */
    public static RelayReading parseRelayText(String text) {
        String[] parts = text.replace(';', ',').split(",");
        if (parts.length != 3) {
            throw new PacketException("текстовый пакет реле должен быть 'voltage,current,state'");
        }
        return new RelayReading(parseNumber(parts[0], "voltage"), parseNumber(parts[1], "current"),
                parseRelayState(parts[2].trim()));
    }
}
