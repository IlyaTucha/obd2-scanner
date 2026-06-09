package com.obd2monitor.domain.model

import java.util.UUID

enum class FuelType(val russianName: String, val density: Float, val afr: Float) {
    PETROL("Бензин", 750f, 14.7f),
    DIESEL("Дизель", 840f, 14.5f),
    GAS("Газ (LPG)", 540f, 15.5f),
    HYBRID("Гибрид", 750f, 14.7f),
    ELECTRIC("Электро", 0f, 0f),
}

/**
 * Профиль автомобиля. См. ТЗ §4.6 и §11.
 */
data class VehicleProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val make: String,
    val model: String,
    val year: Int,
    val engineType: String,
    val displacementL: Float,
    val fuelType: FuelType,
    val tankCapacity: Float,
    val fuelPrice: Float = 0f,
    val normalCoolantTempC: IntRange,
    val normalRpm: IntRange,
    val idleRpm: IntRange,
    val normalBatteryV: ClosedFloatingPointRange<Float>,
    val obdProtocol: OBDProtocol = OBDProtocol.AUTO,
    val supportedPids: List<String> = emptyList(),
    /** Заводской пиковый крутящий момент, Нм. Для расчёта Torque ≈ load% × maxTorqueNm. */
    val maxTorqueNm: Int = 130,
    /** Заводская пиковая мощность, кВт. Для верхней границы шкалы и fallback расчёта. */
    val maxPowerKw: Int = 70,
    /** VIN — 17 символов, опционально (вкладка «Авто»). У пресетов пусто. */
    val vin: String = "",
    /** Государственный номер, опционально. */
    val licensePlate: String = "",
) {
    companion object {
        // Поколения Hyundai Accent (для нижнего ряда годов в названии профиля):
        //   LC = 2000-2005 (3-е поколение, «Verna»)
        //   MC = 2006-2011 (4-е поколение, у нас Accent / в части рынков «Verna»)
        // Внутри одного поколения двигатели и протокол OBD-II не менялись по годам,
        // поэтому отдельных профилей под каждый год нет — они идентичны.

        /**
         * Hyundai Accent (MC, 2006-2011) 1.5 G4EC DOHC. Дефолтный активный профиль.
         */
        fun hyundaiAccentMcG4EC(): VehicleProfile = VehicleProfile(
            id = "preset_hyundai_accent_2008_g4ec",
            name = "Hyundai Accent 1.5 G4EC DOHC (MC, 2006–2011)",
            make = "Hyundai",
            model = "Accent",
            year = 2008,  // представительный год генерации
            engineType = "1.5L G4EC DOHC",
            displacementL = 1.5f,
            fuelType = FuelType.PETROL,
            tankCapacity = 43f,
            normalCoolantTempC = 83..97,
            normalRpm = 750..6500,
            idleRpm = 750..850,
            normalBatteryV = 13.0f..14.8f,
            obdProtocol = OBDProtocol.AUTO,
            supportedPids = listOf("01", "04", "05", "0C", "0D", "0F", "10", "11", "1F", "21", "2F", "33", "42", "45", "46"),
            maxTorqueNm = 138,  // G4EC DOHC: 138 Нм @ 3000
            maxPowerKw = 75,    // G4EC DOHC: 102 л.с. = 75 кВт
        )

        /** Hyundai Accent (LC, 2000-2005) 1.5 G4EC SOHC — 90 л.с. */
        fun hyundaiAccentLcG4EC(): VehicleProfile = VehicleProfile(
            id = "preset_hyundai_accent_g4ec",
            name = "Hyundai Accent 1.5 G4EC SOHC (LC, 2000–2005)",
            make = "Hyundai",
            model = "Accent",
            year = 2003,
            engineType = "1.5L G4EC SOHC",
            displacementL = 1.5f,
            fuelType = FuelType.PETROL,
            tankCapacity = 45f,
            normalCoolantTempC = 85..97,
            normalRpm = 750..6200,
            idleRpm = 750..850,
            normalBatteryV = 13.0f..14.8f,
            obdProtocol = OBDProtocol.AUTO,
            supportedPids = listOf("01", "04", "05", "0C", "0D", "0F", "10", "11", "1F", "2F", "33", "42"),
            maxTorqueNm = 124,  // G4EC SOHC: 124 Нм @ 3000
            maxPowerKw = 66,    // G4EC SOHC: 90 л.с. = 66 кВт
        )

        /** Hyundai Accent (MC, 2006-2011) 1.4 G4EE DOHC — 97 л.с. */
        fun hyundaiAccentMcG4EE(): VehicleProfile = VehicleProfile(
            id = "preset_hyundai_accent_2008_g4ee",
            name = "Hyundai Accent 1.4 G4EE DOHC (MC, 2006–2011)",
            make = "Hyundai",
            model = "Accent",
            year = 2008,
            engineType = "1.4L G4EE DOHC",
            displacementL = 1.4f,
            fuelType = FuelType.PETROL,
            tankCapacity = 43f,
            normalCoolantTempC = 83..97,
            normalRpm = 750..5800,
            idleRpm = 750..850,
            normalBatteryV = 13.0f..14.8f,
            obdProtocol = OBDProtocol.AUTO,
            supportedPids = listOf("01", "04", "05", "0C", "0D", "0F", "10", "11", "1F", "21", "2F", "33", "42", "45", "46"),
            maxTorqueNm = 125,  // G4EE: 125 Нм @ 3000
            maxPowerKw = 71,    // G4EE: 97 л.с. = 71 кВт
        )

        /** Hyundai Accent (MC, 2006-2011) 1.6 G4ED DOHC — 112 л.с. */
        fun hyundaiAccentMcG4ED(): VehicleProfile = VehicleProfile(
            id = "preset_hyundai_accent_2008_g4ed",
            name = "Hyundai Accent 1.6 G4ED DOHC (MC, 2006–2011)",
            make = "Hyundai",
            model = "Accent",
            year = 2008,
            engineType = "1.6L G4ED DOHC",
            displacementL = 1.6f,
            fuelType = FuelType.PETROL,
            tankCapacity = 43f,
            normalCoolantTempC = 83..97,
            normalRpm = 750..6200,
            idleRpm = 750..850,
            normalBatteryV = 13.0f..14.8f,
            obdProtocol = OBDProtocol.AUTO,
            supportedPids = listOf("01", "04", "05", "0C", "0D", "0F", "10", "11", "1F", "21", "2F", "33", "42", "45", "46"),
            maxTorqueNm = 145,  // G4ED: 145 Нм @ 3000
            maxPowerKw = 82,    // G4ED: 112 л.с. = 82 кВт
        )

        /**
         * Универсальный OBD-II / EOBD профиль — fallback без привязки к марке.
         * Используется если ни один пресет Accent не подходит или проще выбрать
         * стандарт (как «OBD-II Стандарт EOBD» в OpenDiag/Torque).
         */
        fun genericOBD2(): VehicleProfile = VehicleProfile(
            id = "preset_generic_obd2",
            name = "OBD-II Стандарт (универсальный)",
            make = "Generic",
            model = "OBD-II / EOBD",
            year = 0,
            engineType = "—",
            displacementL = 2.0f,
            fuelType = FuelType.PETROL,
            tankCapacity = 50f,
            normalCoolantTempC = 80..105,
            normalRpm = 700..7000,
            idleRpm = 700..900,
            normalBatteryV = 12.0f..14.8f,
            obdProtocol = OBDProtocol.AUTO,
            supportedPids = emptyList(),
        )

        /** Все предустановленные профили. Первый — активный по умолчанию. */
        fun presets(): List<VehicleProfile> = listOf(
            hyundaiAccentMcG4EC(),  // ← дефолт (1.5 G4EC DOHC, MC 2006-2011)
            hyundaiAccentMcG4EE(),
            hyundaiAccentMcG4ED(),
            hyundaiAccentLcG4EC(),
            genericOBD2(),
        )
    }
}
