package com.obd2monitor.domain.model

enum class DTCCategory(val prefix: Char, val title: String) {
    POWERTRAIN('P', "Двигатель / трансмиссия"),
    BODY('B', "Кузов"),
    CHASSIS('C', "Шасси"),
    NETWORK('U', "Сеть"),
}

enum class DTCStatus(val russianName: String, val hint: String) {
    PENDING("Ожидающая", "Неисправность замечена в одной поездке. Если повторится, код станет подтверждённым и загорится Check Engine."),
    CONFIRMED("Сохранённая", "Неисправность подтверждена и записана в память блока управления."),
    PERMANENT("Постоянная", "Код нельзя стереть сканером: блок управления удалит его сам, когда убедится, что неисправность устранена."),
}

data class DTCCode(
    val code: String,
    val description: String,
    val category: DTCCategory = parseCategory(code),
    val status: DTCStatus = DTCStatus.CONFIRMED,
    val possibleCauses: List<String> = emptyList(),
    val recommendedActions: List<String> = emptyList(),
    val isCritical: Boolean = false,
) {
    companion object {
        private val FORMAT = Regex("^[PCBU][0-3][0-9A-F]{3}$")

        /** Похоже ли на код неисправности: буква P/C/B/U, цифра 0–3 и три hex-цифры. */
        fun isValidCode(code: String): Boolean = FORMAT.matches(code)

        /**
         * Код производителя (SAE J2012): P1xxx, P30xx–P33xx, B1/B2, C1/C2, U1/U2.
         * Остальные — стандартные, с одинаковым смыслом на любой машине.
         */
        fun isManufacturerSpecific(code: String): Boolean {
            val c = code.uppercase()
            val d1 = c.getOrNull(1) ?: return false
            return when (c.firstOrNull()) {
                'P' -> d1 == '1' || (d1 == '3' && (c.getOrNull(2)?.digitToIntOrNull(16) ?: 0) <= 3)
                'B', 'C', 'U' -> d1 == '1' || d1 == '2'
                else -> false
            }
        }

        fun parseCategory(code: String): DTCCategory = when (code.firstOrNull()?.uppercaseChar()) {
            'B' -> DTCCategory.BODY
            'C' -> DTCCategory.CHASSIS
            'U' -> DTCCategory.NETWORK
            else -> DTCCategory.POWERTRAIN
        }
    }
}

/** Монитор самодиагностики ЭБУ: [complete] — проверка пройдена с момента сброса ошибок. */
data class ReadinessMonitor(val name: String, val complete: Boolean)

/** Ответ PID 01 01: лампа Check Engine, число кодов и готовность мониторов. */
data class MonitorStatus(
    val milOn: Boolean,
    val storedDtcCount: Int,
    val diesel: Boolean,
    val monitors: List<ReadinessMonitor>,
)

/** Стоп-кадр: условия работы двигателя в момент фиксации [dtc]. */
data class FreezeFrame(val dtc: String, val values: List<Pair<String, String>>)

/** Итог сканирования ошибок. */
data class DtcScanResult(
    val codes: List<DTCCode>,
    val monitorStatus: MonitorStatus?,
    val freezeFrame: FreezeFrame?,
    /** Сколько из трёх запросов (сохранённые / ожидающие / постоянные) получили ответ. */
    val answeredRequests: Int,
)
