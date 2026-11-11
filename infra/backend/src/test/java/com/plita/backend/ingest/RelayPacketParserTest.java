package com.plita.backend.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plita.backend.ingest.Packets.PacketException;
import com.plita.backend.ingest.Packets.RelayReading;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelayPacketParserTest {

    private final RelayPacketParser parser = new RelayPacketParser(new ObjectMapper());

    private RelayReading parse(String s) {
        return parser.parse(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emulatorJson() {
        RelayReading r = parse("{\"voltage\": 218.9, \"current\": 8.14, \"state\": 1}");
        assertEquals(218.9, r.voltage(), 0);
        assertEquals(8.14, r.current(), 0);
        assertEquals(1, r.state());
    }

    @Test
    void variants() {
        assertEquals(0, parse("{\"U\": 230, \"I\": 0, \"status\": \"off\"}").state());
        assertEquals(1, parse("{\"voltage\": \"221.5\", \"current\": \"3\", \"state\": true}").state());
        assertEquals(1, parse("220.1,4.5,1").state());
    }

    @Test
    void bad() {
        for (String s : new String[]{"", "not json", "{\"voltage\": 1}", "{\"voltage\":1,\"current\":1,\"state\":7}",
                "[1,2,3]", "{\"voltage\": null, \"current\": 1, \"state\": 1}", "{broken"}) {
            assertThrows(PacketException.class, () -> parse(s));
        }
        assertThrows(PacketException.class, () -> parser.parse(new byte[]{(byte) 0xff, (byte) 0xfe}));
    }
}
