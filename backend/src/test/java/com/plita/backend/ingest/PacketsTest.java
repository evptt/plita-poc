package com.plita.backend.ingest;

import com.plita.backend.ingest.Packets.Kind;
import com.plita.backend.ingest.Packets.PacketException;
import com.plita.backend.ingest.Packets.SensorReading;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketsTest {

    @Test
    void topics() {
        assertEquals(Kind.SENSOR, Packets.parseTopic("device/abc/telemetry").kind());
        assertEquals("r1", Packets.parseTopic("relay/r1/power").deviceId());
        assertNull(Packets.parseTopic("relay/r1/command"));
        assertNull(Packets.parseTopic("device//telemetry"));
        assertNull(Packets.parseTopic("$SYS/broker/uptime"));
        assertNull(Packets.parseTopic("device/a/telemetry/extra"));
    }

    @Test
    void sensorPacketLittleEndianFieldOrder() {
        // порядок в пакете: влажность, температура, VOC, радар (раздел 3 ТЗ); как struct.pack('<fffB')
        byte[] raw = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(45.5f).putFloat(23.25f).putFloat(312.0f).put((byte) 1).array();
        SensorReading r = Packets.parseSensorPacket(raw, "little");
        assertEquals(45.5, r.humidity(), 0);
        assertEquals(23.25, r.temperature(), 0);
        assertEquals(312.0, r.voc(), 0);
        assertEquals(1, r.radar());
    }

    @Test
    void sensorPacketBigEndianAndUnsignedRadar() {
        byte[] raw = Packets.buildSensorPacket(10f, 20f, 30f, 255, "big");
        SensorReading r = Packets.parseSensorPacket(raw, "big");
        assertEquals(10.0, r.humidity(), 0);
        assertEquals(255, r.radar());
    }

    @Test
    void sensorPacketWrongSize() {
        for (int n : new int[]{0, 12, 14}) {
            assertThrows(PacketException.class, () -> Packets.parseSensorPacket(new byte[n], "little"));
        }
    }

    @Test
    void sensorPacketNaN() {
        byte[] raw = Packets.buildSensorPacket(Float.NaN, 1f, 1f, 0, "little");
        assertThrows(PacketException.class, () -> Packets.parseSensorPacket(raw, "little"));
    }

    @Test
    void relayTextAndState() {
        assertEquals(1, Packets.parseRelayText("220.1,4.5,1").state());
        assertEquals(0, Packets.parseRelayText("230;0;off").state());
        assertThrows(PacketException.class, () -> Packets.parseRelayText("1,2"));
        assertThrows(PacketException.class, () -> Packets.parseRelayText("[1,2,3]"));
        assertThrows(PacketException.class, () -> Packets.parseRelayState(7));
        assertEquals(1, Packets.parseRelayState(true));
        assertEquals(0, Packets.parseRelayState(0.0));
    }
}
