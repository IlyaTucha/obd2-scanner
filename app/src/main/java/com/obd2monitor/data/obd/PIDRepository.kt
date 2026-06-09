package com.obd2monitor.data.obd

/**
 * Кэш поддерживаемых PID для текущего ECU.
 * Заполняется при инициализации соединения через PID 01 00.
 */
class PIDRepository {
    private var supported: Set<String> = emptySet()

    fun set(pids: List<String>) { supported = pids.map { it.uppercase() }.toSet() }
    fun isSupported(pid: String): Boolean = supported.contains(pid.uppercase())
    fun all(): List<String> = supported.toList().sorted()
    fun clear() { supported = emptySet() }
}
