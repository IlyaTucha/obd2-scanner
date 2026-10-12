package com.obd2monitor.domain.model

import java.util.UUID

/** Запись пробега на конкретную дату — пользователь вводит вручную. */
data class MileageEntry(
    val id: String = UUID.randomUUID().toString(),
    val vehicleProfileId: String,
    /** Дата (ms epoch). Время дня игнорируется в графике, важна сама дата. */
    val date: Long,
    /** Одометр на эту дату, км. */
    val odometerKm: Int,
    val note: String = "",
)

/** Категории расходов (для агрегатов и фильтров на UI). */
enum class ExpenseType(val russianName: String) {
    MAINTENANCE("Ремонт и ТО"),
    FUEL("Топливо"),
    PARTS("Запчасти"),
    INSURANCE("Страховка"),
    TAX("Налог/штраф"),
    WASH("Мойка"),
    TIRES("Шины"),
    OTHER("Прочее");

    companion object {
        fun fromName(name: String): ExpenseType =
            runCatching { valueOf(name) }.getOrDefault(OTHER)
    }
}

/** Расход — что, когда, сколько, на каком пробеге. */
data class ExpenseEntry(
    val id: String = UUID.randomUUID().toString(),
    val vehicleProfileId: String,
    val date: Long,
    val type: ExpenseType,
    /** Описание: «масло Mobil 5W30 4л + фильтр», «бампер», ... */
    val description: String,
    val amountRub: Float,
    /** Пробег на момент расхода (опционально). По умолчанию — из журнала пробега на дату расхода. */
    val odometerKm: Int? = null,
    /** Литры заправки (для [ExpenseType.FUEL]). */
    val fuelLiters: Float? = null,
    /** Заправка до полного бака. */
    val fullTank: Boolean = false,
) {
    /**
     * Литры заправки: из поля, а у старых записей — из описания («40л 92», «35.9 л»),
     * где их писали вручную до появления отдельного поля.
     */
    val liters: Float?
        get() = if (type != ExpenseType.FUEL) null else fuelLiters ?: litersFromText(description)

    companion object {
        private val LITERS = Regex("""(\d+(?:[.,]\d+)?)\s*л(?![а-яё])""", RegexOption.IGNORE_CASE)

        fun litersFromText(text: String): Float? =
            LITERS.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()?.takeIf { it in 1f..200f }
    }
}

/**
 * Категории обслуживания с интервалами по умолчанию (км).
 * Интервалы — типовые для бензинового седана среднего сегмента; пользователь
 * может переопределить в записи (см. MaintenanceEntry.intervalKm).
 */
enum class MaintenanceType(val russianName: String, val defaultIntervalKm: Int) {
    ENGINE_OIL("Моторное масло", 10_000),
    OIL_FILTER("Масляный фильтр", 10_000),
    AIR_FILTER("Воздушный фильтр", 20_000),
    CABIN_FILTER("Салонный фильтр", 15_000),
    FUEL_FILTER("Топливный фильтр", 30_000),
    SPARK_PLUGS("Свечи зажигания", 30_000),
    TRANSMISSION_OIL("Масло в КПП", 60_000),
    BRAKE_FLUID("Тормозная жидкость", 40_000),
    COOLANT("Антифриз", 60_000),
    TIMING_BELT("Ремень ГРМ", 60_000),
    BRAKE_PADS_FRONT("Тормозные колодки (перед)", 30_000),
    BRAKE_PADS_REAR("Тормозные колодки (зад)", 50_000),
    BATTERY("АКБ", 80_000),
    OTHER("Прочее", 20_000);

    companion object {
        fun fromName(name: String): MaintenanceType =
            runCatching { valueOf(name) }.getOrDefault(OTHER)
    }
}

/** Запись о выполненном обслуживании с пробегом замены и интервалом до следующей. */
data class MaintenanceEntry(
    val id: String = UUID.randomUUID().toString(),
    val vehicleProfileId: String,
    val type: MaintenanceType,
    val date: Long,
    /** Пробег на момент замены (км). */
    val odometerKm: Int,
    /** Интервал до следующей замены (км). Если 0 — берём defaultIntervalKm. */
    val intervalKm: Int,
    val note: String = "",
) {
    /** Пробег на котором нужна следующая замена. */
    val nextDueKm: Int get() = odometerKm + (if (intervalKm > 0) intervalKm else type.defaultIntervalKm)
}
