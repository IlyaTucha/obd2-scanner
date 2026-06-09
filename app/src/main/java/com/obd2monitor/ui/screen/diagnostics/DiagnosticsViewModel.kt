package com.obd2monitor.ui.screen.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.domain.model.DTCCode
import com.obd2monitor.domain.usecase.ClearDTCUseCase
import com.obd2monitor.domain.usecase.ReadDTCCodesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(
    val codes: List<DTCCode> = emptyList(),
    val isScanning: Boolean = false,
    val lastScanAt: Long? = null,
    val message: String? = null,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val readDTC: ReadDTCCodesUseCase,
    private val clearDTC: ClearDTCUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    fun scan() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanning = true, message = null)
            runCatching { readDTC() }
                .onSuccess { _state.value = DiagnosticsUiState(codes = it, lastScanAt = System.currentTimeMillis()) }
                .onFailure {
                    _state.value = _state.value.copy(
                        isScanning = false,
                        message = it.message ?: "Ошибка чтения DTC"
                    )
                }
        }
    }

    fun clear() {
        viewModelScope.launch {
            val ok = runCatching { clearDTC() }.getOrDefault(false)
            _state.value = _state.value.copy(
                codes = if (ok) emptyList() else _state.value.codes,
                message = if (ok) "Ошибки сброшены" else "Сбросить не удалось",
            )
        }
    }
}
