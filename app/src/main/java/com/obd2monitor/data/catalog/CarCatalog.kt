package com.obd2monitor.data.catalog

import android.content.Context
import com.obd2monitor.domain.model.FuelType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray

/**
 * Запись справочника популярных авто. Один объект = одно поколение конкретного
 * двигателя на конкретной модели. Год можно фильтровать через [yearFrom]..[yearTo].
 *
 * Источник данных — `assets/car_catalog.json`. JSON редактируется отдельно
 * (не требует пересборки кода) — это позволяет расширять каталог одной правкой
 * файла, без диффа в Kotlin. Загрузка ленивая, кеш в памяти (Singleton).
 */
data class CatalogEntry(
    val make: String,
    val model: String,
    val yearFrom: Int,
    val yearTo: Int,
    /** Человекочитаемое название двигателя — попадает в `engineType` профиля. */
    val engine: String,
    /** Рабочий объём, литры. */
    val displacement: Float,
    val fuel: FuelType,
    /** Объём бака, литры. */
    val tank: Float,
    /** Заводская пиковая мощность, кВт. */
    val powerKw: Int,
    /** Заводской пиковый момент, Нм. */
    val torqueNm: Int,
)

/**
 * Каталог авто. Singleton — данные читаются один раз при первом обращении,
 * парсятся из `assets/car_catalog.json` и кешируются на всё время жизни
 * процесса. ~150 записей по ~80 байт = ~12 КБ в памяти, парсинг занимает
 * < 30 мс на современном Android — не критично, но всё равно lazy чтобы не
 * блокировать старт приложения.
 *
 * Парсер — `org.json.JSONArray` из Android SDK (без kotlinx.serialization),
 * чтобы не тащить лишнюю зависимость и не настраивать @Serializable. JSON
 * простой и обратно совместимый — добавление новых полей в файл не сломает
 * текущий код (мы читаем только известные ключи), удаление поля — пропуск
 * записи через try/catch.
 */
@Singleton
class CarCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val entries: List<CatalogEntry> by lazy { loadFromAssets() }

    private fun loadFromAssets(): List<CatalogEntry> {
        return runCatching {
            val raw = context.assets.open(ASSET_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val entry = runCatching {
                        CatalogEntry(
                            make = obj.getString("make"),
                            model = obj.getString("model"),
                            yearFrom = obj.getInt("yearFrom"),
                            yearTo = obj.getInt("yearTo"),
                            engine = obj.getString("engine"),
                            displacement = obj.getDouble("displacement").toFloat(),
                            fuel = FuelType.valueOf(obj.optString("fuel", "PETROL")),
                            tank = obj.getDouble("tank").toFloat(),
                            powerKw = obj.getInt("powerKw"),
                            torqueNm = obj.getInt("torqueNm"),
                        )
                    }.getOrNull()
                    if (entry != null) add(entry)
                }
            }.sortedWith(compareBy({ it.make }, { it.model }, { it.yearFrom }, { it.engine }))
        }.getOrElse { emptyList() }
    }

    /** Уникальные марки, отсортированные алфавитом. */
    fun makes(): List<String> = entries.map { it.make }.distinct().sorted()

    /** Модели для конкретной марки. */
    fun models(make: String): List<String> =
        entries.filter { it.make == make }.map { it.model }.distinct().sorted()

    /**
     * Поколения для марки+модели — представляются строкой «YYYY–YYYY».
     * Раздельная сортировка по yearFrom, чтобы в дропдауне было ровно
     * как у автопроизводителей (старые в конце).
     */
    fun generations(make: String, model: String): List<String> =
        entries.filter { it.make == make && it.model == model }
            .map { "${it.yearFrom}–${it.yearTo}" }
            .distinct()

    /** Все двигатели для конкретной марки+модели+поколения. */
    fun engines(make: String, model: String, generation: String): List<CatalogEntry> {
        val (from, to) = parseGeneration(generation) ?: return emptyList()
        return entries.filter { it.make == make && it.model == model && it.yearFrom == from && it.yearTo == to }
            .sortedBy { it.displacement }
    }

    private fun parseGeneration(text: String): Pair<Int, Int>? {
        val parts = text.split("–", "-")
        if (parts.size != 2) return null
        val from = parts[0].toIntOrNull() ?: return null
        val to = parts[1].toIntOrNull() ?: return null
        return from to to
    }

    private companion object {
        const val ASSET_FILE = "car_catalog.json"
    }
}
