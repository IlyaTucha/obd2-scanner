package com.obd2monitor

import com.obd2monitor.domain.model.ExpenseEntry
import com.obd2monitor.domain.model.ExpenseType
import com.obd2monitor.domain.model.FuelEconomy
import com.obd2monitor.domain.model.TripFuelRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelEconomyTest {

    private fun fill(odo: Int, liters: Float?, desc: String = "", full: Boolean = false, date: Long = odo.toLong()) =
        ExpenseEntry(
            vehicleProfileId = "p", date = date, type = ExpenseType.FUEL, description = desc,
            amountRub = (liters ?: 40f) * 65f, odometerKm = odo, fuelLiters = liters, fullTank = full,
        )

    @Test
    fun litersParsedFromDescription() {
        assertEquals(40f, ExpenseEntry.litersFromText("40л 92 1л 64.16р")!!, 0.001f)
        assertEquals(35.9f, ExpenseEntry.litersFromText("35.9 л, полный бак")!!, 0.001f)
        assertEquals(30f, ExpenseEntry.litersFromText("30 л АИ-95, скидка")!!, 0.001f)
        assertNull(ExpenseEntry.litersFromText("заправка после покупки"))
    }

    @Test
    fun overallExcludesLastFill() {
        val e = FuelEconomy.compute(
            listOf(fill(1000, 40f), fill(1400, 40f), fill(1800, 30f)),
            emptyList(),
        )!!
        // 80 л сожжено на 800 км: последние 30 л ещё в баке.
        assertEquals(10f, e.overall.lPer100, 0.01f)
    }

    @Test
    fun tanksBetweenFullFillsIncludeTopUps() {
        val e = FuelEconomy.compute(
            listOf(
                fill(1000, 20f, full = true),
                fill(1200, 15f), // доливка не до полного
                fill(1500, 35f, full = true),
                fill(1900, 36f, full = true),
            ),
            emptyList(),
        )!!
        assertEquals(2, e.tanks.size)
        assertEquals(50f / 500f * 100f, e.tanks[0].span.lPer100, 0.01f) // 15 + 35 л на 500 км
        assertEquals(9f, e.lastTank!!.span.lPer100, 0.01f) // 36 л на 400 км
    }

    @Test
    fun descriptionLitersAndObdComparison() {
        val e = FuelEconomy.compute(
            listOf(fill(1000, null, "40л"), fill(1500, null, "40 л")),
            listOf(TripFuelRow(startTime = 1200, distanceKm = 100f, fuelUsedLiters = 7.2f)),
        )!!
        assertEquals(8f, e.overall.lPer100, 0.01f)
        assertEquals(7.2f, e.overallObd!!.lPer100, 0.01f)
    }

    @Test
    fun tooShortSpanGivesNothing() {
        assertNull(FuelEconomy.compute(listOf(fill(1000, 40f), fill(1100, 40f)), emptyList()))
    }

    @Test
    fun sinceLastFillUsesJournalMileage() {
        val e = FuelEconomy.compute(
            listOf(fill(1000, 40f), fill(1500, 40f)),
            listOf(
                TripFuelRow(startTime = 1200, distanceKm = 100f, fuelUsedLiters = 8f),
                TripFuelRow(startTime = 1600, distanceKm = 50f, fuelUsedLiters = 4.5f),
            ),
            currentOdoKm = 1700,
        )!!
        val s = e.sinceLastFill!!
        assertEquals(200, s.km)
        assertEquals(16f, s.estimatedLiters, 0.01f) // 200 км × средние 8 л/100
    }
}
