package com.obd2monitor.domain.repository

import com.obd2monitor.domain.model.ConnectionStatus
import com.obd2monitor.domain.model.DTCCode
import com.obd2monitor.domain.model.EcuInfo
import com.obd2monitor.domain.model.OBDData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface OBDRepository {
    val connectionStatus: StateFlow<ConnectionStatus>
    val liveData: Flow<OBDData>

    suspend fun connect(deviceAddress: String)
    suspend fun disconnect()
    /**
     * Переподключение к последнему адресу без сброса активной сессии.
     * Использует deviceAddress из текущего ConnectionStatus; если он null —
     * операция no-op. Состояние переходит CONNECTED → CONNECTING → INITIALIZING →
     * CONNECTED, минуя DISCONNECTED — поэтому AutoSessionManager НЕ закрывает trip,
     * а только ставит на паузу на время реинициализации.
     */
    suspend fun reconnect()
    suspend fun setPollInterval(intervalMs: Long)

    suspend fun readDTC(): List<DTCCode>
    suspend fun clearDTC(): Boolean
    suspend fun supportedPids(): List<String>
    suspend fun readEcuInfo(): EcuInfo
}
