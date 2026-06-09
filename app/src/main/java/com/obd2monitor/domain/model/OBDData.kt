package com.obd2monitor.domain.model

/**
 * Полный снимок live-данных OBD2 в момент времени.
 * null означает — PID недоступен на этом ECU.
 */
data class OBDData(
    val timestamp: Long = System.currentTimeMillis(),
    val speedKmh: Float? = null,         // PID 01 0D
    val rpm: Int? = null,                // PID 01 0C
    val coolantTempC: Int? = null,       // PID 01 05
    val intakeTempC: Int? = null,        // PID 01 0F
    val mafGs: Float? = null,            // PID 01 10
    val mapKpa: Int? = null,             // PID 01 0B / 33
    val throttlePercent: Float? = null,  // PID 01 11
    val engineLoadPercent: Float? = null,// PID 01 04
    val fuelLevelPercent: Float? = null, // PID 01 2F
    val batteryVoltage: Float? = null,   // PID 01 42
    val ignitionTimingDeg: Float? = null,// PID 01 0E
    val instantFuelLPer100: Float? = null,
    /** Мгновенный расход в л/ч — для отображения когда скорость <5 км/ч (л/100км теряет смысл).
     *  Считается из MAF/MAP/PID 5E как и L/100, но не делится на скорость → корректен на стоянке. */
    val instantFuelLPerH: Float? = null,
    val oilTempC: Int? = null,           // PID 01 5C
    val shortFuelTrim1Pct: Float? = null,// PID 01 06
    val longFuelTrim1Pct: Float? = null, // PID 01 07
    val distanceMilKm: Int? = null,      // PID 01 21
    val distanceSinceClearKm: Int? = null,// PID 01 31
    val ambientTempC: Int? = null,       // PID 01 46 — температура за бортом

    // ── Расчётные метрики (не PID; вычисляются в OBDRepositoryImpl). ──────────
    /** Мгновенная мощность двигателя, кВт. Из MAF (если есть) либо load% × maxPower. */
    val powerKw: Float? = null,
    /** Мгновенный крутящий момент, Нм. Из мощности и RPM. */
    val torqueNm: Float? = null,
    /** Вакуум во впуске, кПа. Для атмосферных = 101 − MAP. На холостом ~70, на полном газу ~0. */
    val vacuumKpa: Float? = null,
    /** Прогрев двигателя 0..100% (по coolantTempC относительно нижней границы рабочего диапазона профиля). */
    val warmupPct: Float? = null,
    /** Cut-off: ECU отключил форсунки (накатом, throttle≈0, RPM > idle). */
    val fuelCutoffActive: Boolean = false,
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR, INITIALIZING }

data class ConnectionStatus(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val elmVersion: String? = null,
    val protocol: OBDProtocol? = null,
    val errorMessage: String? = null,
    /** Сколько PID отдал ECU на 0100/0120/0140 (или 0 — ECU не ответил). */
    val supportedPidsCount: Int = 0,
    /** Время последнего полученного непустого снимка данных от ECU. */
    val lastSnapshotAt: Long? = null,
    /** В последнем снимке хоть один PID отдал значение. */
    val lastSnapshotHadData: Boolean = false,
    /** Последний сырой ответ ELM на 0100 — для диагностики "ECU не отвечает". */
    val initRawResponse: String? = null,
)

enum class OBDProtocol(val atCommand: String, val displayName: String) {
    AUTO("ATSP0", "Auto"),
    SAE_J1850_PWM("ATSP1", "SAE J1850 PWM"),
    SAE_J1850_VPW("ATSP2", "SAE J1850 VPW"),
    ISO_9141_2("ATSP3", "ISO 9141-2"),
    ISO_14230_4_KWP_5BAUD("ATSP4", "KWP 5-baud"),
    ISO_14230_4_KWP_FAST("ATSP5", "KWP Fast"),
    ISO_15765_4_CAN_11_500("ATSP6", "CAN 11/500"),
    ISO_15765_4_CAN_29_500("ATSP7", "CAN 29/500"),
    ISO_15765_4_CAN_11_250("ATSP8", "CAN 11/250"),
    ISO_15765_4_CAN_29_250("ATSP9", "CAN 29/250"),
}
