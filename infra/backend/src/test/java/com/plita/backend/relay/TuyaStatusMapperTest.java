package com.plita.backend.relay;

import com.plita.backend.ingest.Packets.PacketException;
import com.plita.backend.ingest.Packets.RelayReading;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuyaStatusMapperTest {

    private final TuyaStatusMapper mapper = new TuyaStatusMapper(TuyaStatusMapper.Codes.defaults());

    @Test
    void standardCodes() {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("switch", true);
        st.put("cur_current", 8140L);     // мА
        st.put("cur_voltage", 2189L);     // 0,1 В
        st.put("cur_power", 17800L);
        RelayReading r = mapper.map(st);
        assertEquals(1, r.state());
        assertEquals(8.14, r.current(), 1e-9);
        assertEquals(218.9, r.voltage(), 1e-9);
    }

    /** Пример из документации Tuya: 08 80 00 03 E8 00 27 10 = 217,6 В, 1,000 А. */
    @Test
    void phaseAFromDocs() {
        TuyaStatusMapper.PhaseA p = TuyaStatusMapper.decodePhaseA("CIAAA+gAJxA=");
        assertEquals(217.6, p.voltage(), 1e-9);
        assertEquals(1.0, p.current(), 1e-9);
        assertEquals(10000, p.powerRaw(), 1e-9);
    }

    /** Автомат без cur_current/cur_voltage: ток и напряжение из phase_a, выключатель switch_1. */
    @Test
    void phaseAFallback() {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("switch_1", "false");
        st.put("phase_a", "CWgABtwAABE=");
        st.put("total_forward_energy", 1234L);
        RelayReading r = mapper.map(st);
        assertEquals(0, r.state());
        assertEquals(240.8, r.voltage(), 1e-9);
        assertEquals(1.756, r.current(), 1e-9);
    }

    @Test
    void customCodesAndScales() {
        TuyaStatusMapper m = new TuyaStatusMapper(new TuyaStatusMapper.Codes("switch_1", "current", 1.0, "voltage", 1.0));
        RelayReading r = m.map(Map.of("switch_1", 1L, "current", "0.4", "voltage", 221.5));
        assertEquals(1, r.state());
        assertEquals(0.4, r.current(), 1e-9);
        assertEquals(221.5, r.voltage(), 1e-9);
    }

    @Test
    void missingCodesExplainWhatIsThere() {
        PacketException e = assertThrows(PacketException.class,
                () -> mapper.map(Map.of("switch", true, "fault", 0L)));
        assertTrue(e.getMessage().contains("fault"), e.getMessage());
        assertThrows(PacketException.class, () -> mapper.map(Map.of("cur_current", 1L, "cur_voltage", 2200L)));
        assertThrows(PacketException.class, () -> TuyaStatusMapper.decodePhaseA("AAA="));
        assertThrows(PacketException.class, () -> TuyaStatusMapper.decodePhaseA("@@@"));
    }
}
