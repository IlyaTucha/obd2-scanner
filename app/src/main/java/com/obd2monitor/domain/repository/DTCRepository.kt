package com.obd2monitor.domain.repository

import com.obd2monitor.domain.model.DTCCode

interface DTCRepository {
    /** Локальный справочник: возвращает описание/причины/действия по коду. */
    suspend fun describe(code: String): DTCCode?
    suspend fun describeAll(codes: List<String>): List<DTCCode>
}
