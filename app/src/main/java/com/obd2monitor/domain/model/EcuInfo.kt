package com.obd2monitor.domain.model

/**
 * Информация о блоке ECU, опрашиваемая через Mode 09.
 *  - vin: идентификатор машины (PID 0902).
 *  - calibrationId: версия прошивки ECU (PID 0904).
 *  - ecuName: имя контроллера (PID 090A).
 *  - protocol: текущий протокол OBD2.
 *  - elmVersion: прошивка адаптера ELM327.
 *  - supportedPids: PID Mode 01, которые ECU отдаёт.
 */
data class EcuInfo(
    val vin: String? = null,
    val calibrationId: String? = null,
    val ecuName: String? = null,
    val protocol: OBDProtocol? = null,
    val elmVersion: String? = null,
    val supportedPids: List<String> = emptyList(),
)
