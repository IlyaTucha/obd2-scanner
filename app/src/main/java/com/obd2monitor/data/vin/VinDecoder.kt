package com.obd2monitor.data.vin

import com.obd2monitor.domain.model.FuelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Результат расшифровки VIN. Любое поле может быть null если не удалось определить
 * (онлайн-API не знает марку либо в локальном fallback-таблице нет такого WMI).
 */
data class VinDecodeResult(
    val vin: String,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val engineType: String? = null,
    val displacementL: Float? = null,
    val fuelType: FuelType? = null,
    val maxPowerKw: Int? = null,
    /** true если данные взяты из API NHTSA vPIC, false — локальный fallback (только WMI+год). */
    val online: Boolean,
    /** Причина неудачи если всё совсем не получилось — для UI-сообщения. */
    val errorMessage: String? = null,
) {
    val hasAnyData: Boolean
        get() = make != null || model != null || year != null || engineType != null
}

/**
 * Расшифровка VIN. Сначала пытается онлайн через бесплатный NHTSA vPIC API
 * (https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVin/{vin}?format=json).
 * При недоступности сети / API падает на локальный парсинг:
 *   - 10-й символ → модельный год (1980-2039)
 *   - первые 3 символа (WMI) → производитель из таблицы наиболее частых.
 *
 * NHTSA лучше всего знает машины для рынка США/Канады/Европы; японские и
 * корейские бренды (Hyundai/KIA/Toyota/Honda/Nissan) обычно расшифровывает
 * хорошо. Российские/китайские — может вернуть пустые поля → используем
 * локальный fallback.
 */
@Singleton
class VinDecoder @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Проверка валидности по длине и алфавиту (без I/O/Q как в стандарте ISO 3779). */
    fun isValid(vin: String): Boolean {
        if (vin.length != 17) return false
        return vin.all { it.isDigit() || (it in 'A'..'Z' && it !in setOf('I', 'O', 'Q')) }
    }

    suspend fun decode(vin: String): VinDecodeResult {
        val v = vin.uppercase().trim()
        if (!isValid(v)) {
            return VinDecodeResult(
                vin = v,
                online = false,
                errorMessage = "VIN должен быть 17 символов (без I, O, Q)",
            )
        }
        // Онлайн-запрос с таймаутом 5 сек — если сети нет, падаем на локальный.
        // Обязательно через Dispatchers.IO — иначе HttpURLConnection кинет
        // NetworkOnMainThreadException (suspend сам по себе main-thread не уводит).
        val online = withTimeoutOrNull(5_000L) {
            withContext(Dispatchers.IO) {
                runCatching { fetchNhtsa(v) }.getOrNull()
            }
        }
        if (online != null && online.hasAnyData) return online
        return decodeOffline(v)
    }

    /** Локальная расшифровка — без сети. Только год + грубая марка по WMI. */
    fun decodeOffline(vin: String): VinDecodeResult {
        val v = vin.uppercase().trim()
        if (!isValid(v)) {
            return VinDecodeResult(vin = v, online = false, errorMessage = "Невалидный VIN")
        }
        return VinDecodeResult(
            vin = v,
            make = wmiToMake(v.substring(0, 3)),
            year = decodeYear(v[9]),
            online = false,
        )
    }

    // ───────── NHTSA vPIC ─────────

    private fun fetchNhtsa(vin: String): VinDecodeResult? {
        val url = URL("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVin/$vin?format=json")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = json.decodeFromString<NhtsaResponse>(body)
            val map = parsed.results.orEmpty().associate { it.variable to it.value.orEmpty() }

            val make = map["Make"].niceOrNull()?.toTitleCase()
            val model = map["Model"].niceOrNull()
            val year = map["Model Year"].niceOrNull()?.toIntOrNull()
            val displacementL = map["Displacement (L)"].niceOrNull()?.toFloatOrNull()
            val cyl = map["Engine Number of Cylinders"].niceOrNull()?.toIntOrNull()
            val fuelStr = map["Fuel Type - Primary"].niceOrNull()
            val powerHp = map["Engine Brake (hp) From"].niceOrNull()?.toFloatOrNull()
                ?: map["Engine Power (kW) From"].niceOrNull()?.toFloatOrNull()?.let { it * 1.341f }
            val engineModel = map["Engine Model"].niceOrNull()

            val engineDescParts = mutableListOf<String>()
            displacementL?.let { engineDescParts += "%.1fL".format(it) }
            engineModel?.takeIf { it.isNotBlank() }?.let { engineDescParts += it }
            cyl?.let { engineDescParts += "${it}-цил." }
            val engineType = engineDescParts.joinToString(" ").ifBlank { null }

            val finalYear = year ?: decodeYear(vin[9])
            val apiHadData = make != null || model != null || year != null || engineType != null
            // Сообщение «API не нашло» — только если совсем ничего не определилось,
            // включая локальный fallback по 10-му символу. Иначе год светится зелёным
            // как успех — а в подзаголовке «ошибка», что выглядит противоречиво.
            val anyDataAtAll = apiHadData || finalYear != null
            val msg = when {
                anyDataAtAll && !apiHadData ->
                    "API не вернул марку/модель — определён только год"
                !anyDataAtAll ->
                    "API не нашло данных по этому VIN"
                else -> null
            }
            return VinDecodeResult(
                vin = vin,
                make = make,
                model = model,
                year = finalYear,
                engineType = engineType,
                displacementL = displacementL,
                fuelType = fuelStr?.let(::mapFuel),
                maxPowerKw = powerHp?.let { (it / 1.341f).toInt() },
                online = true,
                errorMessage = msg,
            )
        } finally {
            conn.disconnect()
        }
    }

    // ───────── Helpers ─────────

    /**
     * Расшифровка модельного года из 10-го символа VIN (ISO 3779).
     * Цикл ~30 лет: 1980→A, 1981→B, ..., 1999→X, 2000→Y, 2001→1, ..., 2009→9,
     * 2010→A снова и т.д. Возвращаем самый свежий вариант, не превышающий
     * следующий календарный год (модельный 2026 уже продают в 2025).
     */
    private fun decodeYear(c: Char): Int? {
        val table = listOf(
            'A' to 1980, 'B' to 1981, 'C' to 1982, 'D' to 1983, 'E' to 1984,
            'F' to 1985, 'G' to 1986, 'H' to 1987, 'J' to 1988, 'K' to 1989,
            'L' to 1990, 'M' to 1991, 'N' to 1992, 'P' to 1993, 'R' to 1994,
            'S' to 1995, 'T' to 1996, 'V' to 1997, 'W' to 1998, 'X' to 1999,
            'Y' to 2000, '1' to 2001, '2' to 2002, '3' to 2003, '4' to 2004,
            '5' to 2005, '6' to 2006, '7' to 2007, '8' to 2008, '9' to 2009,
        )
        val base = table.firstOrNull { it.first == c }?.second ?: return null
        // Возможные годы: base, base+30, base+60. Берём ближайший к «сейчас+1».
        val candidates = listOf(base, base + 30, base + 60)
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return candidates.filter { it <= now + 1 }.maxOrNull() ?: base
    }

    private fun mapFuel(raw: String): FuelType? {
        val s = raw.lowercase()
        return when {
            "gasoline" in s || "petrol" in s -> FuelType.PETROL
            "diesel" in s -> FuelType.DIESEL
            "lpg" in s || "propane" in s || "cng" in s -> FuelType.GAS
            "hybrid" in s -> FuelType.HYBRID
            "electric" in s || "ev" in s || "bev" in s -> FuelType.ELECTRIC
            else -> null
        }
    }

    /**
     * WMI (3 первых символа) → производитель. Без претензии на полноту —
     * типовые марки + Hyundai/KIA (целевая для проекта). Полная база — десятки
     * тысяч кодов, тащить её сюда нет смысла, для всего остального онлайн-API.
     */
    private fun wmiToMake(wmi: String): String? = when (wmi.uppercase()) {
        // Hyundai / KIA — приоритет, тк это профильные машины проекта
        "KMH", "KM8", "KMF", "KMC", "KME" -> "Hyundai"
        "5NP", "5NM", "5XX", "5NL" -> "Hyundai"  // США-сборка
        "X7M", "Z94" -> "Hyundai"  // Россия (Калининград) и др. сборка
        "KNA", "KND", "KNE", "KNB", "5XY" -> "KIA"
        // Остальные мейджоры
        "JTD", "JT2", "JT3", "JT4", "JTH", "JTJ", "JTK", "JTL", "JTM", "JTN" -> "Toyota"
        "JHM", "JHL", "JHN", "JH4", "JH2", "JHE" -> "Honda"
        "JN1", "JN6", "JN8", "1N4", "1N6", "VNK" -> "Nissan"
        "WBA", "WBS", "WBY", "5UX", "4US" -> "BMW"
        "WDB", "WDD", "WDC", "WDF", "55S", "4JG" -> "Mercedes-Benz"
        "WAU", "WA1", "WUA", "TRU" -> "Audi"
        "WVW", "WV1", "WV2", "WVG", "1VW", "3VW", "XW8" -> "Volkswagen"
        "WP0", "WP1" -> "Porsche"
        "VF1", "VF2", "VF3", "VF6", "VF8", "X4X", "X9F" -> "Renault"
        "VF7" -> "Peugeot"
        "ZFA", "ZFF" -> "Fiat"
        "1FT", "1FA", "1FM", "2FA", "2FM" -> "Ford"
        "1G1", "1GC", "1GN" -> "Chevrolet"
        "XTA", "XTT" -> "Lada"  // АвтоВАЗ
        else -> null
    }

    private fun String?.niceOrNull(): String? =
        this?.takeIf { it.isNotBlank() && !it.equals("Not Applicable", true) && it != "0" }

    private fun String.toTitleCase(): String = lowercase()
        .split(' ', '-')
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    @Serializable
    private data class NhtsaResponse(
        @kotlinx.serialization.SerialName("Results") val results: List<NhtsaItem>? = null,
    )

    @Serializable
    private data class NhtsaItem(
        @kotlinx.serialization.SerialName("Variable") val variable: String? = null,
        @kotlinx.serialization.SerialName("Value") val value: String? = null,
    )
}
