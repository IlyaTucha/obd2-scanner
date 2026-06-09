package com.obd2monitor.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.obd2monitor.domain.model.ConsumptionUnit
import com.obd2monitor.domain.model.PressureUnit
import com.obd2monitor.domain.model.SpeedUnit
import com.obd2monitor.domain.model.TempUnit
import com.obd2monitor.domain.model.UnitsPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val LAST_DEVICE = stringPreferencesKey("last_device_address")
        val LAST_DEVICE_NAME = stringPreferencesKey("last_device_name")
        val POLL_INTERVAL_MS = longPreferencesKey("poll_interval_ms")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val UNIT_SPEED = stringPreferencesKey("unit_speed")
        val UNIT_TEMP = stringPreferencesKey("unit_temp")
        val UNIT_CONSUMPTION = stringPreferencesKey("unit_consumption")
        val UNIT_PRESSURE = stringPreferencesKey("unit_pressure")
    }

    val lastDeviceAddress: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_DEVICE] }
    val lastDeviceName: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_DEVICE_NAME] }
    // Минимальный интервал между snapshot-ами. Сами snapshot-ы (5 hot PID) занимают
    // ~100-300 ms, так что фактический рейт ~3-7 Hz. delay(50) — просто breathing space
    // чтобы не забивать ELM подряд.
    val pollIntervalMs: Flow<Long> = context.dataStore.data.map { it[Keys.POLL_INTERVAL_MS] ?: 50L }
    val autoConnect: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_CONNECT] ?: true }

    val units: Flow<UnitsPrefs> = context.dataStore.data.map { p ->
        UnitsPrefs(
            speed = enumOrDefault(p[Keys.UNIT_SPEED], SpeedUnit.KMH),
            temp = enumOrDefault(p[Keys.UNIT_TEMP], TempUnit.CELSIUS),
            consumption = enumOrDefault(p[Keys.UNIT_CONSUMPTION], ConsumptionUnit.L_PER_100KM),
            pressure = enumOrDefault(p[Keys.UNIT_PRESSURE], PressureUnit.KPA),
        )
    }

    suspend fun setLastDevice(address: String?, name: String?) {
        context.dataStore.edit { p ->
            if (address == null) p.remove(Keys.LAST_DEVICE) else p[Keys.LAST_DEVICE] = address
            if (name == null) p.remove(Keys.LAST_DEVICE_NAME) else p[Keys.LAST_DEVICE_NAME] = name
        }
    }

    suspend fun setPollIntervalMs(ms: Long) = context.dataStore.edit { it[Keys.POLL_INTERVAL_MS] = ms }
    suspend fun setAutoConnect(value: Boolean) = context.dataStore.edit { it[Keys.AUTO_CONNECT] = value }

    suspend fun setUnits(prefs: UnitsPrefs) = context.dataStore.edit { p ->
        p[Keys.UNIT_SPEED] = prefs.speed.name
        p[Keys.UNIT_TEMP] = prefs.temp.name
        p[Keys.UNIT_CONSUMPTION] = prefs.consumption.name
        p[Keys.UNIT_PRESSURE] = prefs.pressure.name
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(value: String?, default: E): E =
        value?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
