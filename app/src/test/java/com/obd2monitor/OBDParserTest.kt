package com.obd2monitor

import com.obd2monitor.data.obd.OBDParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OBDParserTest {

    @Test
    fun `K-line response with padding`() {
        assertEquals(listOf("P0133", "P0171"), OBDParser.parseDTCList("43 01 33 01 71 00 00\r\r>", 3))
    }

    @Test
    fun `K-line several frames each with mode byte`() {
        val raw = "43013301710300\r43042000000000\r"
        assertEquals(listOf("P0133", "P0171", "P0300", "P0420"), OBDParser.parseDTCList(raw, 3))
    }

    @Test
    fun `CAN single frame with count byte`() {
        assertEquals(listOf("P0133"), OBDParser.parseDTCList("43 01 01 33", 3))
        assertEquals(emptyList<String>(), OBDParser.parseDTCList("4300", 3))
    }

    @Test
    fun `CAN multi frame response is not lost`() {
        // 4 кода: 1 (mode) + 1 (count) + 8 = 10 байт = 0x00A
        val raw = "00A\r0:430401330171\r1:03000420AAAAAA\r\r>"
        assertEquals(listOf("P0133", "P0171", "P0300", "P0420"), OBDParser.parseDTCList(raw, 3))
    }

    @Test
    fun `two ECUs answer separately without garbage codes`() {
        // Раньше вторая строка «43 00» превращалась в несуществующий C0300.
        assertEquals(listOf("P0133"), OBDParser.parseDTCList("43 01 01 33\r43 00\r", 3))
    }

    @Test
    fun `pending and permanent modes and letters`() {
        assertEquals(listOf("P0420"), OBDParser.parseDTCList("47 01 04 20", 7))
        assertEquals(listOf("U0100"), OBDParser.parseDTCList("4A 01 C1 00", 0x0A))
        assertEquals("C0035", OBDParser.decodeDtc(0x40, 0x35))
        assertEquals("B1342", OBDParser.decodeDtc(0x93, 0x42))
    }

    @Test
    fun `no data gives empty list`() {
        assertEquals(emptyList<String>(), OBDParser.parseDTCList("NO DATA\r\r>", 3))
        assertEquals(emptyList<String>(), OBDParser.parseDTCList("SEARCHING...\rUNABLE TO CONNECT", 3))
    }

    @Test
    fun `monitor status - MIL on, two codes, spark ignition`() {
        // A=0x82: MIL + 2 кода; B=0x07: все три непрерывных монитора есть и готовы;
        // C=0x65: катализатор, EVAP, O2, подогрев O2; D=0x21: катализатор и O2 не готовы.
        val st = OBDParser.parseMonitorStatus("41 01 82 07 65 21")
        assertNotNull(st)
        st!!
        assertTrue(st.milOn)
        assertEquals(2, st.storedDtcCount)
        assertFalse(st.diesel)
        val byName = st.monitors.associate { it.name to it.complete }
        assertEquals(true, byName["Пропуски зажигания"])
        assertEquals(false, byName["Катализатор"])
        assertEquals(true, byName["Система EVAP"])
        assertEquals(false, byName["Кислородные датчики"])
        assertEquals(true, byName["Подогрев кислородных датчиков"])
        assertNull(byName["EGR / VVT"])
    }

    @Test
    fun `monitor status - not answered`() {
        assertNull(OBDParser.parseMonitorStatus("NO DATA"))
    }

    @Test
    fun `freeze frame dtc`() {
        assertEquals("P0301", OBDParser.parseFreezeFrameDtc("42 02 00 03 01"))
        assertNull(OBDParser.parseFreezeFrameDtc("42 02 00 00 00"))
        assertEquals(listOf(0x1A, 0xF8), OBDParser.freezePayload("42 0C 00 1A F8", 0x0C))
    }

    @Test
    fun `mode 01 values still parse`() {
        assertEquals(1726, OBDParser.parseRpm("410C1AF8"))
        assertEquals(60, OBDParser.parseSpeed("41 0D 3C"))
        assertNull(OBDParser.parseSpeed("41 0D FF"))
    }
}
