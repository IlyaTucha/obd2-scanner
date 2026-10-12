package com.obd2monitor.data.obd

/**
 * OBD2 PID-команды (Mode 01) и AT-команды ELM327.
 * См. ТЗ §5.2.
 */
enum class OBDCommand(val pid: String, val command: String, val responseBytes: Int) {
    // Mode 01 — текущие данные
    ENGINE_LOAD("0104", "0104", 1),
    COOLANT_TEMP("0105", "0105", 1),
    INTAKE_MAP("010B", "010B", 1),
    RPM("010C", "010C", 2),
    SPEED("010D", "010D", 1),
    TIMING_ADVANCE("010E", "010E", 1),
    INTAKE_TEMP("010F", "010F", 1),
    MAF("0110", "0110", 2),
    THROTTLE("0111", "0111", 1),
    FUEL_LEVEL("012F", "012F", 1),
    BATTERY_VOLTAGE("0142", "0142", 2),
    AMBIENT_TEMP("0146", "0146", 1),
    FUEL_RATE("015E", "015E", 2),
    OIL_TEMP("015C", "015C", 1),
    DISTANCE_WITH_MIL("0121", "0121", 2),
    DISTANCE_SINCE_CLEAR("0131", "0131", 2),

    // Mode 03 — DTC, Mode 04 — clear DTC
    READ_DTC("03", "03", -1),
    READ_PENDING_DTC("07", "07", -1),
    READ_PERMANENT_DTC("0A", "0A", -1),
    CLEAR_DTC("04", "04", -1),

    // Mode 09 — паспорт ECU (VIN, calibration ID, имя контроллера)
    VIN("0902", "0902", -1),
    CALIBRATION_ID("0904", "0904", -1),
    ECU_NAME("090A", "090A", -1),
}

object ELMAtCommand {
    const val RESET = "ATZ"
    const val ECHO_OFF = "ATE0"
    const val LINEFEEDS_OFF = "ATL0"
    const val HEADERS_OFF = "ATH0"
    const val SPACES_OFF = "ATS0"
    const val ADAPTIVE_TIMING = "ATAT1"
    const val DESCRIBE_PROTOCOL = "ATDPN"
    const val VERSION = "ATI"
    /** Чтение напряжения на разъёме OBD непосредственно адаптером ELM. Работает всегда. */
    const val READ_VOLTAGE = "ATRV"
}
