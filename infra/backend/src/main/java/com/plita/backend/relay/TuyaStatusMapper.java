package com.plita.backend.relay;

import com.plita.backend.ingest.Packets;
import com.plita.backend.ingest.Packets.PacketException;
import com.plita.backend.ingest.Packets.RelayReading;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Статус автомата из Tuya Cloud (список code/value) -> отсчёт реле: напряжение, ток, On/Off.
 * Без внешних зависимостей.
 *
 * <p>Коды DP у разных автоматов разные. Сначала берутся коды из настроек (TUYA_SWITCH_CODE,
 * TUYA_CURRENT_CODE, TUYA_VOLTAGE_CODE), для выключателя ещё запасные {@code switch} и {@code switch_1}.
 * Если тока или напряжения нет, но есть сырой {@code phase_a} (так делают многие автоматы на Tuya),
 * они берутся из него: 8 байт big-endian, напряжение 2 байта x 0,1 В, ток 3 байта x 0,001 А, мощность 3 байта.
 * Какие коды у нашего Tongou TO-Q-SY2-JWT, видно в логе бэкенда при первом опросе
 * и в консоли Tuya IoT Platform (Device Debugging).
 */
public final class TuyaStatusMapper {

    public static final String PHASE_A = "phase_a";
    private static final List<String> SWITCH_FALLBACK = List.of("switch", "switch_1");

    /** Коды DP и множители: значение из Tuya * scale = ампер / вольт. */
    public record Codes(String switchCode, String currentCode, double currentScale,
                        String voltageCode, double voltageScale) {

        public static Codes defaults() {
            return new Codes("switch", "cur_current", 0.001, "cur_voltage", 0.1);
        }
    }

    /** Значения из сырого phase_a. */
    public record PhaseA(double voltage, double current, double powerRaw) {
    }

    private final Codes codes;

    public TuyaStatusMapper(Codes codes) {
        this.codes = codes;
    }

    public Codes codes() {
        return codes;
    }

    public RelayReading map(Map<String, Object> status) {
        Integer state = switchState(status);
        Double current = scaled(status.get(codes.currentCode()), codes.currentScale(), "ток");
        Double voltage = scaled(status.get(codes.voltageCode()), codes.voltageScale(), "напряжение");
        if ((current == null || voltage == null) && status.get(PHASE_A) instanceof String raw && !raw.isBlank()) {
            PhaseA p = decodePhaseA(raw);
            current = current == null ? p.current() : current;
            voltage = voltage == null ? p.voltage() : voltage;
        }
        if (state == null) {
            throw new PacketException("в статусе Tuya нет выключателя (" + codes.switchCode() + "), есть коды: "
                    + new TreeSet<>(status.keySet()));
        }
        if (current == null || voltage == null) {
            throw new PacketException("в статусе Tuya нет " + (current == null ? "тока (" + codes.currentCode() : "напряжения ("
                    + codes.voltageCode()) + " или " + PHASE_A + "), есть коды: " + new TreeSet<>(status.keySet()));
        }
        return new RelayReading(voltage, current, state);
    }

    private Integer switchState(Map<String, Object> status) {
        if (status.containsKey(codes.switchCode())) {
            return Packets.parseRelayState(status.get(codes.switchCode()));
        }
        for (String c : SWITCH_FALLBACK) {
            if (status.containsKey(c)) {
                return Packets.parseRelayState(status.get(c));
            }
        }
        return null;
    }

    private static Double scaled(Object value, double scale, String field) {
        if (value == null) {
            return null;
        }
        return Packets.parseNumber(value, field) * scale;
    }

    /** phase_a: base64 от 8 байт big-endian - напряжение (2 байта, 0,1 В), ток (3 байта, 0,001 А), мощность (3 байта). */
    public static PhaseA decodePhaseA(String base64) {
        byte[] b;
        try {
            b = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new PacketException("phase_a не base64: " + base64);
        }
        if (b.length < 8) {
            throw new PacketException("phase_a короче 8 байт: " + b.length);
        }
        int voltage = (b[0] & 0xFF) << 8 | (b[1] & 0xFF);
        int current = (b[2] & 0xFF) << 16 | (b[3] & 0xFF) << 8 | (b[4] & 0xFF);
        int power = (b[5] & 0xFF) << 16 | (b[6] & 0xFF) << 8 | (b[7] & 0xFF);
        return new PhaseA(voltage * 0.1, current * 0.001, power);
    }
}
