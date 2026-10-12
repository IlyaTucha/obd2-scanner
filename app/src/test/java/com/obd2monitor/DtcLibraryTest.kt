package com.obd2monitor

import com.obd2monitor.data.database.DtcLibrary
import com.obd2monitor.domain.model.DTCCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcLibraryTest {

    private val all = DtcLibrary.all()
    private val byCode = all.associateBy { it.code }

    @Test
    fun `library is large, unique and well-formed`() {
        assertTrue("кодов: ${all.size}", all.size >= 780)
        assertEquals(all.size, byCode.size)
        all.forEach { e ->
            assertTrue("неверный формат ${e.code}", DTCCode.isValidCode(e.code))
            assertTrue("пустое описание ${e.code}", e.description.isNotBlank())
            assertFalse("восклицательный знак в ${e.code}", e.description.contains('!'))
        }
    }

    @Test
    fun `families resolve to standard meanings`() {
        assertTrue(byCode.getValue("P0309").description.contains("цилиндре 9"))
        assertTrue(byCode.getValue("P0309").isCritical)
        assertTrue(byCode.getValue("P2307").description.contains("Катушка зажигания C"))
        assertTrue(byCode.getValue("P0163").description.contains("банк 2, датчик 3"))
        assertTrue(byCode.getValue("P0344").description.contains("прерывистый"))
        // В стандарте P0239 — датчик наддува B, а не «прерывистый сигнал» датчика A.
        assertFalse(byCode.containsKey("P0239") && byCode.getValue("P0239").description.contains("наддува A"))
    }

    @Test
    fun `hand-written descriptions win over templates`() {
        assertEquals("Бедная смесь, банк 1", byCode.getValue("P0171").description)
    }

    @Test
    fun `manufacturer codes are marked as brand dependent`() {
        assertTrue(DTCCode.isManufacturerSpecific("P1004"))
        assertTrue(DTCCode.isManufacturerSpecific("P3000"))
        assertFalse(DTCCode.isManufacturerSpecific("P3400"))
        assertTrue(DTCCode.isManufacturerSpecific("B2100"))
        assertFalse(DTCCode.isManufacturerSpecific("U0100"))
        all.filter { DTCCode.isManufacturerSpecific(it.code) }.forEach {
            assertTrue(it.code, it.description.startsWith("Код производителя"))
        }
    }
}
