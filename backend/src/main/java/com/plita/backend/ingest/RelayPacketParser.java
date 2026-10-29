package com.plita.backend.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plita.backend.ingest.Packets.PacketException;
import com.plita.backend.ingest.Packets.RelayReading;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Пакет реле relay/{relay_id}/power. Формат в ТЗ не задан (вопрос 3), поэтому весь разбор здесь.
 *
 * <p>Поддерживается JSON эмулятора {"voltage": 218.9, "current": 8.14, "state": 1} (а также
 * синонимы U / I / status, state "on"/"off") и запасной текстовый вариант "218.9,8.14,1".
 * Когда заказчик зафиксирует формат, менять нужно только этот класс.
 */
public final class RelayPacketParser {

    private static final List<String> VOLTAGE_KEYS = List.of("voltage", "u", "v");
    private static final List<String> CURRENT_KEYS = List.of("current", "i", "a");
    private static final List<String> STATE_KEYS = List.of("state", "status", "relay_state", "on");

    private final ObjectMapper mapper;

    public RelayPacketParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public RelayReading parse(byte[] payload) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString().trim();
        } catch (CharacterCodingException e) {
            throw new PacketException("пакет реле не является текстом/JSON");
        }
        if (text.isEmpty()) {
            throw new PacketException("пустой пакет реле");
        }
        if (!text.startsWith("{")) {
            return Packets.parseRelayText(text);
        }

        JsonNode node;
        try {
            node = mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new PacketException("битый JSON реле: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw new PacketException("JSON реле должен быть объектом");
        }
        Map<String, JsonNode> lowered = new java.util.HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            var e = it.next();
            lowered.put(e.getKey().toLowerCase(), e.getValue());
        }
        double voltage = Packets.parseNumber(value(pick(lowered, VOLTAGE_KEYS, "voltage")), "voltage");
        double current = Packets.parseNumber(value(pick(lowered, CURRENT_KEYS, "current")), "current");
        int state = Packets.parseRelayState(value(pick(lowered, STATE_KEYS, "state")));
        return new RelayReading(voltage, current, state);
    }

    private static JsonNode pick(Map<String, JsonNode> obj, List<String> keys, String field) {
        for (String k : keys) {
            if (obj.containsKey(k)) {
                return obj.get(k);
            }
        }
        throw new PacketException("в пакете реле нет поля " + field);
    }

    private static Object value(JsonNode n) {
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        if (n.isTextual()) {
            return n.textValue();
        }
        return n.toString();
    }
}
