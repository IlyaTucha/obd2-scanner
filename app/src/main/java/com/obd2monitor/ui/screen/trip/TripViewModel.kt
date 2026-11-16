package com.obd2monitor.ui.screen.trip

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.data.database.DatabaseBackupManager
import com.obd2monitor.domain.model.ConnectionStatus
import com.obd2monitor.domain.model.OBDData
import com.obd2monitor.domain.model.Trip
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.repository.TripRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import com.obd2monitor.service.ActiveTripTracker
import com.obd2monitor.service.AutoSessionManager
import com.obd2monitor.service.LiveTripStats
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

data class TripUiState(
    val activeTrip: Trip? = null,
    val activeProfile: VehicleProfile? = null,
    val connection: ConnectionStatus = ConnectionStatus(),
    val live: OBDData = OBDData(),
    val stats: LiveTripStats = LiveTripStats(),
    val durationMs: Long = 0L,
    val pausedMs: Long = 0L,
    val paused: Boolean = false,
    /** Timestamp начала текущей непрерывной паузы, либо null. UI вычитает из (start + AUTO_FINISH)
     *  чтобы показать сколько осталось до авто-завершения сессии. */
    val pauseSinceMs: Long? = null,
    /** Пауза поставлена кнопкой «Пауза»: без авто-завершения. */
    val manualPause: Boolean = false,
    /** Сессия записывается по GPS (начата без адаптера). */
    val gpsMode: Boolean = false,
    /** OBD-сессия временно пишется по GPS: адаптер не на связи, а машина едет. */
    val gpsFallback: Boolean = false,
    val lastGpsFixAt: Long? = null,
)

@HiltViewModel
class TripViewModel @Inject constructor(
    private val tripRepo: TripRepository,
    vehicleRepo: VehicleProfileRepository,
    obdRepo: OBDRepository,
    controller: TripController,
    tracker: ActiveTripTracker,
    private val sessions: AutoSessionManager,
    private val backup: DatabaseBackupManager,
) : ViewModel() {

    private val _backupMessage = MutableStateFlow<String?>(null)
    /** Одноразовое уведомление об успехе/ошибке экспорта-импорта. UI читает и сбрасывает. */
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()
    fun consumeBackupMessage() { _backupMessage.value = null }

    /** Экспорт ТОЛЬКО поездок (trips + obd_readings) в JSON.
     *  Полный бэкап БД делается из настроек, не отсюда. */
    fun exportTrips(uri: Uri) {
        viewModelScope.launch {
            backup.exportTripsTo(uri)
                .onSuccess { n -> _backupMessage.value = "Экспортировано сессий: $n" }
                .onFailure { _backupMessage.value = "Не удалось экспортировать: ${it.message}" }
        }
    }

    /** Импорт поездок из JSON. Существующие поездки с тем же id заменяются.
     *  Профили/пробег/расходы/ТО не трогаются — это отдельный полный бэкап. */
    fun importTrips(uri: Uri) {
        viewModelScope.launch {
            backup.importTripsFrom(uri)
                .onSuccess { n -> _backupMessage.value = "Импортировано сессий: $n" }
                .onFailure { _backupMessage.value = "Не удалось импортировать: ${it.message}" }
        }
    }

    private val _state = MutableStateFlow(TripUiState())
    val state: StateFlow<TripUiState> = _state.asStateFlow()

    /** Завершить сессию (в любом режиме: OBD, GPS, на паузе без связи). */
    fun finish() {
        viewModelScope.launch { sessions.finishByUser() }
    }

    fun pause() {
        viewModelScope.launch { sessions.pauseByUser() }
    }

    fun resume() {
        viewModelScope.launch { sessions.resumeByUser() }
    }

    /** Начать запись маршрута по GPS без адаптера. */
    fun startGpsTrip() {
        viewModelScope.launch { sessions.startGpsTrip() }
    }

    val trips: StateFlow<List<Trip>> = tripRepo.observeTrips().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    init {
        fun update(block: TripUiState.() -> TripUiState) { _state.value = _state.value.block() }
        tripRepo.observeActiveTrip().onEach { update { copy(activeTrip = it) } }.launchIn(viewModelScope)
        tracker.stats.onEach { update { copy(stats = it) } }.launchIn(viewModelScope)
        vehicleRepo.observeActive().onEach { update { copy(activeProfile = it) } }.launchIn(viewModelScope)
        obdRepo.connectionStatus.onEach { update { copy(connection = it) } }.launchIn(viewModelScope)
        obdRepo.liveData.onEach { update { copy(live = it) } }.launchIn(viewModelScope)
        controller.durationMs.onEach { update { copy(durationMs = it) } }.launchIn(viewModelScope)
        controller.pausedMs.onEach { update { copy(pausedMs = it) } }.launchIn(viewModelScope)
        controller.paused.onEach { update { copy(paused = it) } }.launchIn(viewModelScope)
        controller.pauseSinceMs.onEach { update { copy(pauseSinceMs = it) } }.launchIn(viewModelScope)
        controller.manualPause.onEach { update { copy(manualPause = it) } }.launchIn(viewModelScope)
        controller.gpsMode.onEach { update { copy(gpsMode = it) } }.launchIn(viewModelScope)
        controller.gpsFallback.onEach { update { copy(gpsFallback = it) } }.launchIn(viewModelScope)
        controller.lastGpsFixAt.onEach { update { copy(lastGpsFixAt = it) } }.launchIn(viewModelScope)
    }

    fun deleteTrip(id: String) {
        viewModelScope.launch { tripRepo.deleteTrip(id) }
    }
}
