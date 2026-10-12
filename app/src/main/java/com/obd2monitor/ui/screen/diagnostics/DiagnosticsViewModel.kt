package com.obd2monitor.ui.screen.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.domain.model.ConnectionState
import com.obd2monitor.domain.model.DTCCode
import com.obd2monitor.domain.model.DtcScanResult
import com.obd2monitor.domain.repository.DTCRepository
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.domain.usecase.ClearDTCUseCase
import com.obd2monitor.domain.usecase.ScanDtcUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(
    val connected: Boolean = false,
    val result: DtcScanResult? = null,
    val isScanning: Boolean = false,
    val isClearing: Boolean = false,
    val lastScanAt: Long? = null,
    val message: String? = null,
    /** Расшифровка кода, введённого вручную. */
    val lookup: DTCCode? = null,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val scanDtc: ScanDtcUseCase,
    private val clearDTC: ClearDTCUseCase,
    private val dtcRepo: DTCRepository,
    obdRepo: OBDRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        obdRepo.connectionStatus.onEach {
            _state.value = _state.value.copy(connected = it.state == ConnectionState.CONNECTED)
        }.launchIn(viewModelScope)
    }

    fun scan() {
        if (_state.value.isScanning) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanning = true, message = null)
            runCatching { scanDtc() }
                .onSuccess {
                    _state.value = _state.value.copy(
                        result = it,
                        isScanning = false,
                        lastScanAt = System.currentTimeMillis(),
                        message = if (it.answeredRequests < 3) {
                            "Блок управления ответил не на все запросы — часть кодов могла не прочитаться."
                        } else null,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        isScanning = false,
                        message = it.message ?: "Не удалось прочитать ошибки",
                    )
                }
        }
    }

    /** Сброс ошибок; после подтверждённого сброса — повторное чтение. */
    fun clear() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isClearing = true, message = null)
            val ok = runCatching { clearDTC() }.getOrDefault(false)
            _state.value = _state.value.copy(
                isClearing = false,
                message = if (ok) "Ошибки сброшены" else
                    "Блок управления не подтвердил сброс. Обычно сброс выполняется при включённом зажигании и заглушенном двигателе.",
            )
            if (ok) scan()
        }
    }

    fun lookup(raw: String) {
        val code = raw.trim().uppercase()
        if (!DTCCode.isValidCode(code)) {
            _state.value = _state.value.copy(lookup = null)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(lookup = dtcRepo.describe(code))
        }
    }
}
