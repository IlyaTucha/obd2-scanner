package com.obd2monitor.ui.screen.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.data.catalog.CarCatalog
import com.obd2monitor.data.vin.VinDecodeResult
import com.obd2monitor.data.vin.VinDecoder
import com.obd2monitor.domain.model.ExpenseEntry
import com.obd2monitor.domain.model.ExpenseType
import com.obd2monitor.domain.model.FuelType
import com.obd2monitor.domain.model.MaintenanceEntry
import com.obd2monitor.domain.model.MaintenanceType
import com.obd2monitor.domain.model.MileageEntry
import com.obd2monitor.domain.model.VehicleProfile
import com.obd2monitor.domain.repository.CarLogRepository
import com.obd2monitor.domain.repository.VehicleProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MaintenanceStatus(
    val entry: MaintenanceEntry,
    val currentOdoKm: Int,
    val remainingKm: Int,
) {
    /** ok | soon (<= 1500 км до замены) | overdue (пробег уже превышен) */
    val urgency: Urgency = when {
        remainingKm < 0 -> Urgency.OVERDUE
        remainingKm <= 1500 -> Urgency.SOON
        else -> Urgency.OK
    }
    enum class Urgency { OK, SOON, OVERDUE }
}

/** Состояние расшифровки VIN — для UI (диалог редактирования). */
sealed interface VinDecodeUiState {
    data object Idle : VinDecodeUiState
    data object Loading : VinDecodeUiState
    data class Done(val result: VinDecodeResult) : VinDecodeUiState
}

data class CarUiState(
    val profile: VehicleProfile? = null,
    val mileage: List<MileageEntry> = emptyList(),
    val expenses: List<ExpenseEntry> = emptyList(),
    val maintenance: List<MaintenanceEntry> = emptyList(),
    val vinDecode: VinDecodeUiState = VinDecodeUiState.Idle,
) {
    /** Текущий одометр = последняя по дате запись пробега. 0 если записей нет. */
    val currentOdometerKm: Int get() = mileage.maxByOrNull { it.date }?.odometerKm ?: 0

    /** Суммы расходов по категории. */
    val expensesByType: Map<ExpenseType, Float>
        get() = expenses.groupBy { it.type }.mapValues { (_, list) -> list.sumOf { it.amountRub.toDouble() }.toFloat() }

    val totalExpenses: Float get() = expenses.sumOf { it.amountRub.toDouble() }.toFloat()

    val maintenanceStatus: List<MaintenanceStatus>
        get() {
            val odo = currentOdometerKm
            // Для каждой категории берём последнюю замену — именно она определяет следующий срок.
            return maintenance
                .groupBy { it.type }
                .map { (_, list) -> list.maxBy { it.odometerKm } }
                .map { e -> MaintenanceStatus(entry = e, currentOdoKm = odo, remainingKm = e.nextDueKm - odo) }
                .sortedBy { it.remainingKm }
        }
}

@HiltViewModel
class CarViewModel @Inject constructor(
    private val vehicleRepo: VehicleProfileRepository,
    private val carLogRepo: CarLogRepository,
    private val vinDecoder: VinDecoder,
    /** Каталог авто (JSON в assets) — отдаём UI как property для каскадного picker. */
    val catalog: CarCatalog,
) : ViewModel() {

    private val _state = MutableStateFlow(CarUiState())
    val state: StateFlow<CarUiState> = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mileageFlow = vehicleRepo.observeActive().flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else carLogRepo.observeMileage(p.id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val expensesFlow = vehicleRepo.observeActive().flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else carLogRepo.observeExpenses(p.id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val maintenanceFlow = vehicleRepo.observeActive().flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else carLogRepo.observeMaintenance(p.id)
    }

    init {
        vehicleRepo.observeActive().onEach { profile ->
            _state.value = _state.value.copy(profile = profile)
        }.launchIn(viewModelScope)
        mileageFlow.onEach { _state.value = _state.value.copy(mileage = it) }.launchIn(viewModelScope)
        expensesFlow.onEach { _state.value = _state.value.copy(expenses = it) }.launchIn(viewModelScope)
        maintenanceFlow.onEach { _state.value = _state.value.copy(maintenance = it) }.launchIn(viewModelScope)
    }

    fun saveVehicleInfo(vin: String, licensePlate: String) {
        val profile = _state.value.profile ?: return
        // Если в стейте есть последний успешный decode и VIN совпадает — применяем
        // расшифрованные поля поверх профиля (марка/модель/год/двигатель/топливо).
        // Это даёт пользователю «нажал Определить → нажал Сохранить» — и профиль
        // уже сам обновился, без ручного переключения пресетов.
        val decoded = (_state.value.vinDecode as? VinDecodeUiState.Done)?.result
        val cleanedVin = vin.trim().uppercase()
        val merged = if (decoded != null && decoded.vin == cleanedVin) {
            profile.copy(
                vin = cleanedVin,
                licensePlate = licensePlate.trim(),
                make = decoded.make ?: profile.make,
                model = decoded.model ?: profile.model,
                year = decoded.year ?: profile.year,
                engineType = decoded.engineType ?: profile.engineType,
                displacementL = decoded.displacementL ?: profile.displacementL,
                fuelType = decoded.fuelType ?: profile.fuelType,
                maxPowerKw = decoded.maxPowerKw ?: profile.maxPowerKw,
            )
        } else {
            profile.copy(vin = cleanedVin, licensePlate = licensePlate.trim())
        }
        viewModelScope.launch {
            vehicleRepo.upsert(merged)
            _state.value = _state.value.copy(vinDecode = VinDecodeUiState.Idle)
        }
    }

    /**
     * Расшифровка VIN. Сначала пробует онлайн NHTSA vPIC API, fallback — локальный
     * парсинг 10-го символа (год) и WMI (производитель). Результат кладётся в
     * state.vinDecode и UI показывает превью; при сохранении профиля поля
     * автоматически применяются.
     */
    fun decodeVin(vin: String) {
        _state.value = _state.value.copy(vinDecode = VinDecodeUiState.Loading)
        viewModelScope.launch {
            val result = runCatching { vinDecoder.decode(vin) }.getOrElse { e ->
                vinDecoder.decodeOffline(vin).copy(errorMessage = e.message ?: "Сеть недоступна")
            }
            _state.value = _state.value.copy(vinDecode = VinDecodeUiState.Done(result))
        }
    }

    /** Сбрасываем превью расшифровки (например, при закрытии диалога без сохранения). */
    fun resetVinDecode() {
        _state.value = _state.value.copy(vinDecode = VinDecodeUiState.Idle)
    }

    /**
     * Сохранение профиля целиком — пользователь редактирует марку/модель/год/
     * двигатель/топливо/бак вручную (плюс VIN/гос. номер) на одном диалоге.
     * Это снимает зависимость от хардкод-пресетов: можно завести профиль под
     * любую машину, не дожидаясь добавления её в `VehicleProfile.Companion.presets()`.
     *
     * Полю `name` собирается автоматически из марки/модели/двигателя — иначе
     * в списке в Настройках профиль выглядел бы как «Hyundai Solaris» без
     * указания мотора, и при выборе из нескольких профилей путаница.
     */
    fun saveVehicleFull(
        make: String,
        model: String,
        year: Int,
        engineType: String,
        displacementL: Float,
        fuelType: FuelType,
        tankCapacity: Float,
        vin: String,
        licensePlate: String,
    ) {
        val active = _state.value.profile ?: return
        val cleanMake = make.trim()
        val cleanModel = model.trim()
        val cleanEngine = engineType.trim()
        val displayName = buildString {
            append(cleanMake.ifBlank { "Авто" })
            if (cleanModel.isNotBlank()) append(" ").append(cleanModel)
            if (displacementL > 0f) append(" ").append("%.1f".format(displacementL)).append("L")
            if (cleanEngine.isNotBlank()) append(" ").append(cleanEngine)
        }.trim()
        viewModelScope.launch {
            vehicleRepo.upsert(
                active.copy(
                    name = displayName,
                    make = cleanMake,
                    model = cleanModel,
                    year = year,
                    engineType = cleanEngine,
                    displacementL = displacementL.coerceAtLeast(0f),
                    fuelType = fuelType,
                    tankCapacity = tankCapacity.coerceAtLeast(0f),
                    vin = vin.trim().uppercase(),
                    licensePlate = licensePlate.trim().uppercase(),
                )
            )
        }
    }

    fun addMileage(dateMs: Long, odometerKm: Int, note: String) {
        val profile = _state.value.profile ?: return
        if (odometerKm <= 0) return
        viewModelScope.launch {
            carLogRepo.upsertMileage(
                MileageEntry(
                    vehicleProfileId = profile.id,
                    date = dateMs,
                    odometerKm = odometerKm,
                    note = note,
                )
            )
        }
    }

    fun deleteMileage(id: String) {
        viewModelScope.launch { carLogRepo.deleteMileage(id) }
    }

    fun addExpense(dateMs: Long, type: ExpenseType, description: String, amountRub: Float) {
        val profile = _state.value.profile ?: return
        if (amountRub <= 0f) return
        viewModelScope.launch {
            val odo = carLogRepo.lastMileage(profile.id)?.odometerKm
            carLogRepo.upsertExpense(
                ExpenseEntry(
                    vehicleProfileId = profile.id,
                    date = dateMs,
                    type = type,
                    description = description.trim(),
                    amountRub = amountRub,
                    odometerKm = odo,
                )
            )
        }
    }

    fun deleteExpense(id: String) {
        viewModelScope.launch { carLogRepo.deleteExpense(id) }
    }

    fun addMaintenance(
        dateMs: Long,
        type: MaintenanceType,
        odometerKm: Int,
        intervalKm: Int,
        note: String,
    ) {
        val profile = _state.value.profile ?: return
        if (odometerKm <= 0) return
        viewModelScope.launch {
            carLogRepo.upsertMaintenance(
                MaintenanceEntry(
                    vehicleProfileId = profile.id,
                    type = type,
                    date = dateMs,
                    odometerKm = odometerKm,
                    intervalKm = if (intervalKm > 0) intervalKm else type.defaultIntervalKm,
                    note = note,
                )
            )
        }
    }

    fun deleteMaintenance(id: String) {
        viewModelScope.launch { carLogRepo.deleteMaintenance(id) }
    }

    /**
     * Универсальные upsert-методы для UI, который редактирует существующую
     * запись: передаёт MileageEntry/ExpenseEntry/MaintenanceEntry «как есть» с
     * тем же id, что был. addMileage/addExpense/addMaintenance выше создают
     * новые UUID — здесь же сохраняется существующий, и Room делает REPLACE
     * по PK. Это позволяет одним диалогом обслуживать оба сценария
     * (создание / редактирование).
     */
    fun upsertMileage(entry: MileageEntry) {
        viewModelScope.launch { carLogRepo.upsertMileage(entry) }
    }
    fun upsertExpense(entry: ExpenseEntry) {
        viewModelScope.launch { carLogRepo.upsertExpense(entry) }
    }
    fun upsertMaintenance(entry: MaintenanceEntry) {
        viewModelScope.launch { carLogRepo.upsertMaintenance(entry) }
    }

    /**
     * Импорт ранее экспортированного отчёта (.txt из CarExport). Работает в
     * REPLACE-режиме: ПОЛНОСТЬЮ затирает старые пробег/расходы/обслуживание
     * активного профиля и заливает то, что в файле. Так юзер всегда знает,
     * что результат импорта = содержимому файла, без дублей и наслоений.
     *
     * VIN / гос. номер из файла применяются только если в текущем профиле
     * соответствующие поля ещё пусты — иначе ручной ввод приоритетнее.
     */
    fun importCarReport(result: CarImportResult, onDone: (Int) -> Unit) {
        val profile = _state.value.profile
        if (profile == null) { onDone(0); return }
        viewModelScope.launch {
            carLogRepo.deleteAllForProfile(profile.id)
            result.mileage.forEach {
                carLogRepo.upsertMileage(it.copy(vehicleProfileId = profile.id))
            }
            result.expenses.forEach {
                carLogRepo.upsertExpense(it.copy(vehicleProfileId = profile.id))
            }
            result.maintenance.forEach {
                carLogRepo.upsertMaintenance(it.copy(vehicleProfileId = profile.id))
            }
            // Если в файле обнаружены VIN/гос.номер — обновляем профиль, но
            // только пустые поля (не затираем то, что юзер уже ввёл вручную).
            val vinUpdate = result.vin?.takeIf { profile.vin.isBlank() }
            val plateUpdate = result.licensePlate?.takeIf { profile.licensePlate.isBlank() }
            if (vinUpdate != null || plateUpdate != null) {
                vehicleRepo.upsert(
                    profile.copy(
                        vin = vinUpdate ?: profile.vin,
                        licensePlate = plateUpdate ?: profile.licensePlate,
                    )
                )
            }
            onDone(result.total)
        }
    }
}
