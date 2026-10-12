package com.obd2monitor.domain.model

/** Итоги поездки для сравнения расхода по датчикам с расходом по чекам. */
data class TripFuelRow(val startTime: Long, val distanceKm: Float, val fuelUsedLiters: Float)

/** Расход на отрезке: литры, километры и л/100 км. */
data class FuelSpan(val liters: Float, val km: Int) {
    val lPer100: Float get() = liters / km * 100f
}

/**
 * Полный бак: от одной заправки «до полного» до следующей. Залитое на второй —
 * ровно то, что сожжено между ними (вместе с доливками в промежутке).
 */
data class FuelTank(val fromDate: Long, val toDate: Long, val span: FuelSpan)

/**
 * С последней заправки: пробег по журналу пробега. Сколько сожжено, станет известно
 * только на следующей заправке — до неё оценка по среднему расходу за всё время.
 */
data class SinceLastFill(val date: Long, val km: Int, val estimatedLiters: Float)

/**
 * Расход топлива по заправкам из журнала расходов.
 *
 * За всё время: литры всех заправок, кроме последней (её бензин ещё в баке),
 * делённые на пробег от первой заправки до последней. Уровень в баке на концах
 * неизвестен, но на тысячах километров эта ошибка размывается.
 */
data class FuelEconomy(
    val overall: FuelSpan,
    /** Расчёт датчиков OBD за тот же период (null — поездок с данными мало). */
    val overallObd: FuelSpan?,
    /** Баки между заправками «до полного», от старых к новым. */
    val tanks: List<FuelTank>,
    /** С последней заправки (null — пробег после неё ещё не записан). */
    val sinceLastFill: SinceLastFill?,
) {
    val lastTank: FuelTank? get() = tanks.lastOrNull()

    companion object {
        /** Отрезок короче — результат слишком зависит от уровня в баке. */
        const val MIN_SPAN_KM = 300

        /** [currentOdoKm] — текущий пробег по журналу пробега (0 — неизвестен). */
        fun compute(expenses: List<ExpenseEntry>, trips: List<TripFuelRow>, currentOdoKm: Int = 0): FuelEconomy? {
            val fills = expenses
                .filter { it.type == ExpenseType.FUEL && (it.liters ?: 0f) > 0f && (it.odometerKm ?: 0) > 0 }
                .sortedWith(compareBy({ it.odometerKm }, { it.date }))
            if (fills.size < 2) return null
            val overall = span(fills) ?: return null
            if (overall.km < MIN_SPAN_KM) return null

            val full = fills.indices.filter { fills[it].fullTank }
            val tanks = full.zipWithNext().mapNotNull { (a, b) ->
                val km = fills[b].odometerKm!! - fills[a].odometerKm!!
                if (km <= 0) return@mapNotNull null
                val liters = fills.subList(a + 1, b + 1).sumOf { it.liters!!.toDouble() }.toFloat()
                FuelTank(fills[a].date, fills[b].date, FuelSpan(liters, km))
            }

            val last = fills.last()
            val sinceKm = currentOdoKm - last.odometerKm!!
            val sinceLastFill = if (sinceKm > 0) {
                SinceLastFill(last.date, sinceKm, sinceKm * overall.lPer100 / 100f)
            } else null

            val inRange = trips.filter { it.startTime in fills.first().date..last.date }
            val obdKm = inRange.sumOf { it.distanceKm.toDouble() }
            val overallObd = if (obdKm >= 50) {
                FuelSpan(inRange.sumOf { it.fuelUsedLiters.toDouble() }.toFloat(), obdKm.toInt())
            } else null

            return FuelEconomy(overall, overallObd, tanks, sinceLastFill)
        }

        private fun span(fills: List<ExpenseEntry>): FuelSpan? {
            val km = fills.last().odometerKm!! - fills.first().odometerKm!!
            if (km <= 0) return null
            return FuelSpan(fills.dropLast(1).sumOf { it.liters!!.toDouble() }.toFloat(), km)
        }
    }
}
