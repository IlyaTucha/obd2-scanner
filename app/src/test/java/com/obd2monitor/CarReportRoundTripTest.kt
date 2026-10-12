package com.obd2monitor

import com.obd2monitor.domain.model.ExpenseEntry
import com.obd2monitor.domain.model.ExpenseType
import com.obd2monitor.domain.model.MaintenanceEntry
import com.obd2monitor.domain.model.MaintenanceType
import com.obd2monitor.domain.model.MileageEntry
import com.obd2monitor.ui.screen.car.CarUiState
import com.obd2monitor.ui.screen.car.buildCarReportText
import com.obd2monitor.ui.screen.car.parseCarReport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/** Экспорт отчёта «Авто» → импорт возвращает те же записи. */
class CarReportRoundTripTest {

    private fun at(day: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun exportThenImportKeepsEverything() {
        val mileage = listOf(
            MileageEntry(vehicleProfileId = "p", date = at(24, 9, 15), odometerKm = 299005),
            MileageEntry(vehicleProfileId = "p", date = at(24, 21, 40), odometerKm = 299140, note = "вечер"),
        )
        val expenses = listOf(
            ExpenseEntry(
                vehicleProfileId = "p", date = at(4, 18, 30), type = ExpenseType.FUEL,
                description = "37.52л 92 (327км пред)", amountRub = 2427.54f, odometerKm = 299770,
                fuelLiters = 37.52f, fullTank = true,
            ),
            ExpenseEntry(
                vehicleProfileId = "p", date = at(11, 10, 0), type = ExpenseType.TAX,
                description = "штраф за парковку", amountRub = 3000f, odometerKm = 298451,
            ),
        )
        val maintenance = listOf(
            MaintenanceEntry(
                vehicleProfileId = "p", type = MaintenanceType.OIL_FILTER, date = at(22, 12, 0),
                odometerKm = 298830, intervalKm = 7500, note = "26300-35505",
            ),
        )
        val text = buildCarReportText(CarUiState(mileage = mileage, expenses = expenses, maintenance = maintenance))
        val result = parseCarReport(text)

        assertEquals(mileage.map { it.date to it.odometerKm to it.note }, result.mileage.map { it.date to it.odometerKm to it.note })

        val e = result.expenses.sortedBy { it.date }
        assertEquals(2, e.size)
        assertEquals(at(4, 18, 30), e[0].date)
        assertEquals(2427.54f, e[0].amountRub, 0.001f)
        assertEquals(299770, e[0].odometerKm)
        assertEquals(37.52f, e[0].fuelLiters!!, 0.001f)
        assertEquals(true, e[0].fullTank)
        assertEquals("37.52л 92 (327км пред)", e[0].description)
        assertEquals(ExpenseType.TAX, e[1].type)
        assertEquals(3000f, e[1].amountRub, 0.001f)
        assertEquals(false, e[1].fullTank)

        val m = result.maintenance.single()
        assertEquals(MaintenanceType.OIL_FILTER, m.type)
        assertEquals(7500, m.intervalKm)
        assertEquals("26300-35505", m.note)
        assertEquals(at(22, 12, 0), m.date)
    }

    @Test
    fun oldFormatWithoutTimeStillImports() {
        val text = """
            === ПРОБЕГ ===
            24.08.2026  299140 км
            === ОБСЛУЖИВАНИЕ ===
              22.08.2026  Масляный фильтр: 298830 км, заметка
            === РАСХОДЫ ===
              01.10.2026  Топливо: 2600 ₽ · 300778 км
                  40л
        """.trimIndent()
        val r = parseCarReport(text)
        assertEquals(299140, r.mileage.single().odometerKm)
        assertEquals(MaintenanceType.OIL_FILTER.defaultIntervalKm, r.maintenance.single().intervalKm)
        assertEquals(2600f, r.expenses.single().amountRub, 0.001f)
        assertEquals(40f, r.expenses.single().liters!!, 0.001f)
    }
}
