package com.obd2monitor.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val GPS_TRIP_ID = stringPreferencesKey("gps_trip_id")
        val MANUAL_PAUSE_TRIP_ID = stringPreferencesKey("manual_pause_trip_id")
    }

    val lastDeviceAddress: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_DEVICE] }
    val autoConnect: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_CONNECT] ?: true }
    /** id открытой GPS-сессии — чтобы после перезапуска процесса продолжить её в том же
     *  режиме, а не ждать подключения адаптера. */
    val gpsTripId: Flow<String?> = context.dataStore.data.map { it[Keys.GPS_TRIP_ID] }
    /** id поездки на ручной паузе — чтобы после перезапуска процесса она не закрылась
     *  автоматически через AUTO_FINISH_PAUSE_MS. */
    val manualPauseTripId: Flow<String?> = context.dataStore.data.map { it[Keys.MANUAL_PAUSE_TRIP_ID] }

    suspend fun setLastDevice(address: String?, name: String?) {
        context.dataStore.edit { p ->
            if (address == null) p.remove(Keys.LAST_DEVICE) else p[Keys.LAST_DEVICE] = address
            if (name == null) p.remove(Keys.LAST_DEVICE_NAME) else p[Keys.LAST_DEVICE_NAME] = name
        }
    }

    suspend fun setAutoConnect(value: Boolean) = context.dataStore.edit { it[Keys.AUTO_CONNECT] = value }

    suspend fun setGpsTripId(id: String?) = context.dataStore.edit { p ->
        if (id == null) p.remove(Keys.GPS_TRIP_ID) else p[Keys.GPS_TRIP_ID] = id
    }

    suspend fun setManualPauseTripId(id: String?) = context.dataStore.edit { p ->
        if (id == null) p.remove(Keys.MANUAL_PAUSE_TRIP_ID) else p[Keys.MANUAL_PAUSE_TRIP_ID] = id
    }
}
