package com.obd2monitor.ui.screen.settings

import android.bluetooth.BluetoothDevice
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.data.bluetooth.BluetoothObdConnection
import com.obd2monitor.data.database.DatabaseBackupManager
import com.obd2monitor.data.preferences.UserPreferences
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.model.ConnectionStatus
import com.obd2monitor.domain.model.EcuInfo
import com.obd2monitor.domain.model.UnitsPrefs
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import com.obd2monitor.service.TripController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val pairedDevices: List<DeviceInfo> = emptyList(),
    val units: UnitsPrefs = UnitsPrefs(),
    val pollIntervalMs: Long = 200L,
    val autoConnect: Boolean = true,
    val ecuInfo: EcuInfo? = null,
    val ecuLoading: Boolean = false,
)

data class DeviceInfo(val name: String, val address: String)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val bt: BluetoothObdConnection,
    private val vehicleRepo: VehicleProfileRepository,
    private val obdRepo: OBDRepository,
    private val prefs: UserPreferences,
    private val tripController: TripController,
    private val backup: DatabaseBackupManager,
) : ViewModel() {

    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()
    fun consumeBackupMessage() { _backupMessage.value = null }

    /** Полный экспорт БД (sqlite) — все таблицы: trips, readings, профили,
     *  пробег, расходы, ТО. Доступен из скрытого меню в настройках (4 тапа). */
    fun exportFullDatabase(uri: Uri) {
        viewModelScope.launch {
            backup.exportFullDbTo(uri)
                .onSuccess { bytes -> _backupMessage.value = "Полный экспорт выполнен (${bytes / 1024} КБ)" }
                .onFailure { _backupMessage.value = "Ошибка экспорта: ${it.message}" }
        }
    }

    /** Полный импорт БД. После успеха приложение перезапускается, чтобы Room
     *  открыл новый файл — отсюда нельзя продолжать работу с текущей БД. */
    fun importFullDatabase(uri: Uri) {
        viewModelScope.launch {
            backup.stageFullDbImport(uri)
                .onSuccess {
                    _backupMessage.value = "Импорт принят. Перезапуск…"
                    backup.finalizeFullDbImportAndRestart()
                }
                .onFailure { _backupMessage.value = "Ошибка импорта: ${it.message}" }
        }
    }

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    val profiles: StateFlow<List<VehicleProfile>> = vehicleRepo.observeAll().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val activeProfile: StateFlow<VehicleProfile?> = vehicleRepo.observeActive().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )
    val connection: StateFlow<ConnectionStatus> = obdRepo.connectionStatus.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionStatus()
    )

    init {
        prefs.units.onEach { _state.value = _state.value.copy(units = it) }.launchIn(viewModelScope)
        prefs.pollIntervalMs.onEach { _state.value = _state.value.copy(pollIntervalMs = it) }.launchIn(viewModelScope)
        prefs.autoConnect.onEach { _state.value = _state.value.copy(autoConnect = it) }.launchIn(viewModelScope)
        // При подключении автоматически читаем паспорт ECU.
        obdRepo.connectionStatus.onEach { st ->
            if (st.state == ConnectionState.CONNECTED && _state.value.ecuInfo == null) {
                loadEcuInfo()
            }
            if (st.state == ConnectionState.DISCONNECTED) {
                _state.value = _state.value.copy(ecuInfo = null)
            }
        }.launchIn(viewModelScope)
    }

    fun refreshDevices() {
        val devices = runCatching { bt.pairedDevices().mapNotNull { d -> d.toInfo() } }.getOrDefault(emptyList())
        _state.value = _state.value.copy(pairedDevices = devices)
    }

    @Suppress("MissingPermission")
    private fun BluetoothDevice.toInfo(): DeviceInfo? = try {
        DeviceInfo(name = name ?: "Unknown", address = address)
    } catch (_: SecurityException) { null }

    fun connect(address: String, name: String) {
        viewModelScope.launch {
            prefs.setLastDevice(address, name)
            obdRepo.connect(address)
        }
    }

    fun disconnect() {
        // Кнопка «Откл.» в настройках — явный жест: помечаем как finish, чтобы
        // AutoSessionManager закрыл текущий trip. См. TripController.requestFinish.
        tripController.requestFinish()
        viewModelScope.launch { obdRepo.disconnect() }
    }

    fun setActiveProfile(id: String) {
        viewModelScope.launch { vehicleRepo.setActive(id) }
    }

    /** Сохраняет цену топлива в активный профиль. От неё считаются стоимость сессии и
     *  цена за 100 км на дашборде. Передаём 0 чтобы скрыть карточки стоимости. */
    fun setFuelPrice(rubPerLiter: Float) {
        val active = activeProfile.value ?: return
        viewModelScope.launch {
            vehicleRepo.upsert(active.copy(fuelPrice = rubPerLiter.coerceAtLeast(0f)))
        }
    }

    fun setUnits(prefs: UnitsPrefs) {
        viewModelScope.launch { this@SettingsViewModel.prefs.setUnits(prefs) }
    }

    fun setPollInterval(ms: Long) {
        viewModelScope.launch {
            prefs.setPollIntervalMs(ms)
            obdRepo.setPollInterval(ms)
        }
    }

    fun setAutoConnect(value: Boolean) {
        viewModelScope.launch { prefs.setAutoConnect(value) }
    }

    fun loadEcuInfo() {
        viewModelScope.launch {
            _state.value = _state.value.copy(ecuLoading = true)
            val info = runCatching { obdRepo.readEcuInfo() }.getOrNull()
            _state.value = _state.value.copy(ecuInfo = info, ecuLoading = false)
        }
    }
}
