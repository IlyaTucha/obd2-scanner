package com.obd2monitor.data.repository

import com.obd2monitor.data.database.DTCCodeDao
import com.obd2monitor.domain.model.DTCCode
import com.obd2monitor.domain.repository.DTCRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DTCRepositoryImpl @Inject constructor(
    private val dao: DTCCodeDao,
) : DTCRepository {
    override suspend fun describe(code: String): DTCCode? = dao.getByCode(code.trim().uppercase())?.let {
        it.toDtc()
    } ?: fallback(code.trim().uppercase())

    override suspend fun describeAll(codes: List<String>): List<DTCCode> {
        if (codes.isEmpty()) return emptyList()
        val normalized = codes.map { it.trim().uppercase() }.distinct()
        val found = dao.getByCodes(normalized).associateBy { it.code.uppercase() }
        return normalized.map { code -> found[code]?.toDtc() ?: fallback(code) }
    }

    private fun com.obd2monitor.data.database.DTCCodeEntity.toDtc(): DTCCode = DTCCode(
        code = code,
        description = description,
        possibleCauses = possibleCauses.split(";").map { s -> s.trim() }.filter { s -> s.isNotEmpty() },
        recommendedActions = recommendedActions.split(";").map { s -> s.trim() }.filter { s -> s.isNotEmpty() },
        isCritical = isCritical,
    )

    private fun fallback(code: String): DTCCode {
        standardFallback(code)?.let { return it }
        return DTCCode(
            code = code,
            description = when (code.firstOrNull()) {
                'P' -> "Код неисправности силового агрегата $code. Точного описания в локальном справочнике нет."
                'C' -> "Код неисправности шасси $code. Часто относится к ABS/ESP/4WD или датчикам ходовой части; точное значение зависит от марки."
                'B' -> "Код неисправности кузовной электроники $code. Точное значение зависит от марки и блока."
                'U' -> "Код сетевой неисправности $code. Обычно связан с обменом по CAN/LIN или потерей связи с модулем."
                else -> "Описание кода $code недоступно."
            },
            possibleCauses = listOf("Код производителя", "Код сохранён в отдельном блоке управления", "Нужна расшифровка по марке и модели"),
            recommendedActions = listOf("Повторить сканирование", "Считать ошибки всех блоков специализированным сканером", "Сверить код с сервисной документацией производителя"),
        )
    }

    private fun standardFallback(code: String): DTCCode? {
        val prefix = code.firstOrNull() ?: return null
        val number = code.drop(1).toIntOrNull() ?: return null
        val isManufacturerSpecific = DTCCode.isManufacturerSpecific(code)
        return when (prefix) {
            'P' -> powertrainFallback(code, number, isManufacturerSpecific)
            'C' -> chassisFallback(code, number, isManufacturerSpecific)
            'B' -> bodyFallback(code, number, isManufacturerSpecific)
            'U' -> networkFallback(code, number, isManufacturerSpecific)
            else -> null
        }
    }

    private fun powertrainFallback(code: String, number: Int, manufacturer: Boolean): DTCCode {
        val area = when (number) {
            in 0..299 -> "топливно-воздушная смесь, датчики MAF/MAP/IAT, топливная система или форсунки"
            in 300..399 -> "пропуски зажигания или цепи системы зажигания"
            in 400..499 -> "система токсичности: EGR, EVAP, катализатор или вторичный воздух"
            in 500..599 -> "скорость автомобиля, холостой ход или вспомогательные входные сигналы"
            in 600..699 -> "ECU/PCM, питание блока управления, память или внутренняя электроника"
            in 700..899 -> "трансмиссия или управление АКПП/вариатором"
            in 900..999 -> "резервный или производитель-специфичный диапазон силового агрегата"
            in 1000..1299 -> "производитель-специфичная топливно-воздушная система"
            in 1300..1399 -> "производитель-специфичные пропуски зажигания или зажигание"
            in 1400..1499 -> "производитель-специфичная система токсичности"
            in 1500..1599 -> "производитель-специфичное управление скоростью/холостым ходом"
            in 1600..1699 -> "производитель-специфичные цепи ECU/иммобилайзера/питания"
            in 1700..1899 -> "производитель-специфичная трансмиссия"
            in 2000..2299 -> "дополнительные стандартные коды топливно-воздушной системы"
            in 2300..2399 -> "дополнительные стандартные коды системы зажигания"
            in 2400..2499 -> "дополнительные стандартные коды системы токсичности"
            in 2500..2599 -> "дополнительные стандартные коды скорости/холостого хода"
            in 2600..2699 -> "дополнительные стандартные коды ECU и вспомогательных цепей"
            in 2700..2899 -> "дополнительные стандартные коды трансмиссии"
            in 3000..3499 -> "производитель-специфичные системы двигателя, изменения фаз или отключения цилиндров"
            else -> "силовой агрегат; точный смысл зависит от производителя"
        }
        return DTCCode(
            code = code,
            description = "${if (manufacturer) "Код производителя" else "Стандартный код"} $code, точного описания в справочнике нет. Область: $area.",
            possibleCauses = listOf("Неисправность датчика или исполнительного механизма", "Проводка, разъём, питание или масса", "Механическая неисправность связанной системы", "Ошибка сохранена как следствие другой первичной ошибки"),
            recommendedActions = listOf("Посмотреть стоп-кадр и другие коды", "Проверить питание, массу и разъёмы", "Сравнить текущие показания с нормой", "Уточнить точную расшифровку по марке и двигателю"),
            isCritical = number in 300..399 || number in 600..699,
        )
    }

    private fun chassisFallback(code: String, number: Int, manufacturer: Boolean): DTCCode {
        val area = when (number) {
            in 0..199 -> "датчики скорости колёс, ABS или базовая тормозная система"
            in 200..399 -> "ABS/ESP, гидроблок, датчики ускорения или рулевого угла"
            in 400..599 -> "подвеска, рулевое управление или контроль устойчивости"
            in 600..799 -> "полноприводная система, раздаточная коробка или муфта"
            in 800..999 -> "производитель-специфичная система шасси"
            else -> "система шасси; точное значение зависит от производителя"
        }
        return DTCCode(
            code = code,
            description = "${if (manufacturer) "Код производителя" else "Стандартный код"} шасси $code, точного описания в справочнике нет. Область: $area.",
            possibleCauses = listOf("Датчик колеса/ABS/ESP или исполнительный механизм", "Обрыв, КЗ или окисление разъёма", "Низкое напряжение питания блока", "Механическая проблема узла шасси"),
            recommendedActions = listOf("Считать ошибки блока ABS/ESP/4WD", "Проверить датчики и проводку на колёсах", "Проверить питание и массу блока", "Сверить код с документацией производителя"),
        )
    }

    private fun bodyFallback(code: String, number: Int, manufacturer: Boolean): DTCCode {
        val area = when (number) {
            in 0..199 -> "SRS/подушки безопасности, ремни или базовые кузовные цепи"
            in 200..499 -> "центральный замок, двери, стеклоподъёмники или освещение"
            in 500..799 -> "климат, салонная электроника или комфорт"
            in 800..999 -> "иммобилайзер, BCM или производитель-специфичная кузовная электроника"
            else -> "кузовная электроника; точное значение зависит от производителя"
        }
        return DTCCode(
            code = code,
            description = "${if (manufacturer) "Код производителя" else "Стандартный код"} кузовной электроники $code, точного описания в справочнике нет. Область: $area.",
            possibleCauses = listOf("Неисправность датчика/актуатора кузовной системы", "Проводка или разъёмы", "Сбой BCM/SRS/климатического блока", "Низкое напряжение АКБ"),
            recommendedActions = listOf("Считать ошибки конкретного кузовного блока", "Проверить разъёмы и питание", "Не сбрасывать SRS без диагностики причины", "Уточнить код по марке автомобиля"),
        )
    }

    private fun networkFallback(code: String, number: Int, manufacturer: Boolean): DTCCode {
        val area = when (number) {
            in 0..99 -> "шина CAN/LIN или общая сетевая неисправность"
            in 100..299 -> "потеря связи с одним из блоков управления"
            in 300..499 -> "ошибка передачи данных или некорректное сообщение от блока"
            in 500..999 -> "производитель-специфичная сетевая ошибка"
            else -> "сетевая коммуникация; точное значение зависит от производителя"
        }
        return DTCCode(
            code = code,
            description = "${if (manufacturer) "Код производителя" else "Стандартный"} сетевой код $code, точного описания в справочнике нет. Область: $area.",
            possibleCauses = listOf("Потеря питания или массы одного из блоков", "Обрыв/КЗ CAN или LIN", "Слабый аккумулятор", "Блок не выходит на связь или был отключён"),
            recommendedActions = listOf("Проверить напряжение АКБ", "Считать ошибки всех блоков", "Проверить питание/массу модуля, указанного кодом", "Проверить CAN/LIN проводку при повторении ошибки"),
        )
    }
}
