package com.obd2monitor.domain.model

enum class SpeedUnit(val label: String) { KMH("км/ч"), MPH("mph") }
enum class TempUnit(val label: String) { CELSIUS("°C"), FAHRENHEIT("°F") }
enum class ConsumptionUnit(val label: String) { L_PER_100KM("л/100км"), MPG("mpg"), KM_PER_L("км/л") }
enum class PressureUnit(val label: String) { KPA("кПа"), PSI("psi"), BAR("bar") }

data class UnitsPrefs(
    val speed: SpeedUnit = SpeedUnit.KMH,
    val temp: TempUnit = TempUnit.CELSIUS,
    val consumption: ConsumptionUnit = ConsumptionUnit.L_PER_100KM,
    val pressure: PressureUnit = PressureUnit.KPA,
)
