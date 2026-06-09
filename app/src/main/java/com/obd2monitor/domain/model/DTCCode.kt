package com.obd2monitor.domain.model

enum class DTCCategory(val prefix: Char, val title: String) {
    POWERTRAIN('P', "Двигатель / трансмиссия"),
    BODY('B', "Кузов"),
    CHASSIS('C', "Шасси"),
    NETWORK('U', "Сеть"),
}

enum class DTCStatus(val russianName: String) {
    PENDING("Ожидающая"),
    CONFIRMED("Подтверждённая"),
    PERMANENT("Постоянная"),
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
        fun parseCategory(code: String): DTCCategory = when (code.firstOrNull()?.uppercaseChar()) {
            'B' -> DTCCategory.BODY
            'C' -> DTCCategory.CHASSIS
            'U' -> DTCCategory.NETWORK
            else -> DTCCategory.POWERTRAIN
        }
    }
}
