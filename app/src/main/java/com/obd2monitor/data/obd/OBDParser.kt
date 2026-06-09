package com.obd2monitor.data.obd

import com.obd2monitor.domain.model.DTCCategory

/**
 * Парсинг hex-ответов ELM327. Все формулы — из ТЗ §5.2.
 *
 * Формат ответа OBD2 (Mode 01) после "headers off":
 *   "41 0C 1A F8" — где первый байт = Mode+0x40, второй = PID, далее данные.
 * Парсер игнорирует пробелы, "OK", ">", лишние символы.
 */
object OBDParser {

    /**
     * Очищает ответ ELM327 от мусора и возвращает байты данных.
     *
     * Поддерживает ОБА формата ответа:
     *   - с пробелами (ATS1): "41 00 BE 3E B8 10"
     *   - без пробелов (ATS0): "4100BE3EB810"
     * Мы инициализируем адаптер с ATS0 (spaces off), поэтому 99% ответов
     * приходят склеенной hex-строкой. Ранний баг: фильтр `length == 2`
     * выкидывал такие ответы целиком, и валидный ответ ECU («4100BE3EB810»)
     * выглядел как «ECU не отвечает».
     */
    fun cleanBytes(raw: String): List<Int> {
        val cleaned = raw
            .replace(">", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("SEARCHING...", "", ignoreCase = true)
            .replace("SEARCHING", "", ignoreCase = true)
            .replace("NO DATA", "", ignoreCase = true)
            .replace("STOPPED", "", ignoreCase = true)
            .replace("UNABLE TO CONNECT", "", ignoreCase = true)
            .replace("BUS INIT", "", ignoreCase = true)
            .replace("BUSINIT", "", ignoreCase = true)
            .replace("CAN ERROR", "", ignoreCase = true)
            .replace("ERROR", "", ignoreCase = true)
            .replace("?", " ")
            // Точки игнорируем ТОЛЬКО как отдельный токен (часть SEARCHING/BUS INIT
            // dot-сепаратора), но НЕ внутри hex-строки (там их и не бывает).
            .replace(".", " ")
            .trim()
        if (cleaned.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        for (token in cleaned.split(Regex("\\s+"))) {
            if (token.isEmpty()) continue
            if (!token.matches(Regex("[0-9A-Fa-f]+"))) continue
            if (token.length % 2 != 0) continue  // неравная длина — мусор/обрезок
            var i = 0
            while (i < token.length) {
                out += token.substring(i, i + 2).toInt(16)
                i += 2
            }
        }
        return out
    }

    /** Удаляет prefix Mode+0x40 и PID из ответа Mode 01. */
    private fun payload(bytes: List<Int>, expectedMode: Int, expectedPid: Int?): List<Int> {
        if (bytes.size < 2) return emptyList()
        val modeIdx = bytes.indexOfFirst { it == expectedMode + 0x40 }
        if (modeIdx < 0) return emptyList()
        return if (expectedPid != null) {
            if (bytes.size > modeIdx + 1 && bytes[modeIdx + 1] == expectedPid) {
                bytes.drop(modeIdx + 2)
            } else emptyList()
        } else {
            bytes.drop(modeIdx + 1)
        }
    }

    /** PID 01 04: A * 100 / 255 */
    fun parseEngineLoad(raw: String): Float? = single(raw, 0x04)?.let { it * 100f / 255f }

    /** PID 01 05: A - 40 (°C) */
    fun parseCoolantTemp(raw: String): Int? = single(raw, 0x05)?.let { it - 40 }

    /** PID 01 0B: A (kPa) — давление впуска */
    fun parseMapPressure(raw: String): Int? = single(raw, 0x0B)

    /** PID 01 0C: (256A + B) / 4 */
    fun parseRpm(raw: String): Int? = pair(raw, 0x0C)?.let { (a, b) -> ((a * 256) + b) / 4 }

    /** PID 01 0D: A (км/ч).
     *  0xFF — у многих ECU sentinel «нет данных» (особенно во время инициализации шины
     *  и при кратковременной потере датчика скорости). Без фильтра в UI/историю
     *  проскакивают пики 255 км/ч. */
    fun parseSpeed(raw: String): Int? = single(raw, 0x0D)?.takeIf { it < 0xFF }

    /** PID 01 0E: (A / 2) - 64 (град. до ВМТ) */
    fun parseTimingAdvance(raw: String): Float? = single(raw, 0x0E)?.let { it / 2f - 64f }

    /** PID 01 0F: A - 40 (°C) */
    fun parseIntakeTemp(raw: String): Int? = single(raw, 0x0F)?.let { it - 40 }

    /** PID 01 10: (256A + B) / 100 (г/с) */
    fun parseMaf(raw: String): Float? = pair(raw, 0x10)?.let { (a, b) -> ((a * 256) + b) / 100f }

    /** PID 01 11: A * 100 / 255 (%) */
    fun parseThrottle(raw: String): Float? = single(raw, 0x11)?.let { it * 100f / 255f }

    /** PID 01 1F: 256A + B (сек работы двигателя) */
    fun parseRunTime(raw: String): Int? = pair(raw, 0x1F)?.let { (a, b) -> a * 256 + b }

    /** PID 01 2F: A * 100 / 255 (%) */
    fun parseFuelLevel(raw: String): Float? = single(raw, 0x2F)?.let { it * 100f / 255f }

    /** PID 01 33: A (kPa, атмосферное) */
    fun parseBaroPressure(raw: String): Int? = single(raw, 0x33)

    /** PID 01 42: (256A + B) / 1000 (В) */
    fun parseBatteryVoltage(raw: String): Float? = pair(raw, 0x42)?.let { (a, b) -> (a * 256 + b) / 1000f }

    /**
     * ATRV — напряжение на разъёме OBD по данным самого адаптера ELM.
     * Формат: "12.4V" (иногда без 'V', иногда с лишними пробелами / эхо).
     * Этот источник работает на всех ELM-клонах и не зависит от ECU, поэтому используется
     * как fallback к PID 0142, который многие старые ECU (Hyundai Accent MC и т.п.) не отдают.
     */
    fun parseAtRv(raw: String): Float? {
        val s = raw
            .replace(">", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("V", " ", ignoreCase = true)
            .trim()
        if (s.isBlank()) return null
        val match = Regex("[0-9]+(?:\\.[0-9]+)?").find(s) ?: return null
        val v = match.value.toFloatOrNull() ?: return null
        // Sanity: значения вне 5..20 В — это явно мусор / другой токен.
        return if (v in 5f..20f) v else null
    }

    /** PID 01 46: A - 40 (°C) — окружающий воздух */
    fun parseAmbientTemp(raw: String): Int? = single(raw, 0x46)?.let { it - 40 }

    /** PID 01 5E: (256A + B) * 0.05 л/ч */
    fun parseFuelRateLPerH(raw: String): Float? = pair(raw, 0x5E)?.let { (a, b) -> (a * 256 + b) * 0.05f }

    /** PID 01 5C: A - 40 (°C) — температура масла. */
    fun parseOilTemp(raw: String): Int? = single(raw, 0x5C)?.let { it - 40 }

    /** PID 01 06: (A - 128) * 100 / 128 (%) — Short Term Fuel Trim банк 1. */
    fun parseShortFuelTrim1(raw: String): Float? = single(raw, 0x06)?.let { (it - 128) * 100f / 128f }

    /** PID 01 07: (A - 128) * 100 / 128 (%) — Long Term Fuel Trim банк 1. */
    fun parseLongFuelTrim1(raw: String): Float? = single(raw, 0x07)?.let { (it - 128) * 100f / 128f }

    /** PID 01 21: (256A + B) (км) — пробег с горящим Check Engine. */
    fun parseDistanceWithMil(raw: String): Int? = pair(raw, 0x21)?.let { (a, b) -> a * 256 + b }

    /** PID 01 31: (256A + B) (км) — пробег с момента сброса DTC. */
    fun parseDistanceSinceClear(raw: String): Int? = pair(raw, 0x31)?.let { (a, b) -> a * 256 + b }

    /**
     * Mode 09 — текстовые ответы (VIN, calibration ID, ECU name).
     * Ответ на CAN приходит многострочно с префиксами "0:", "1:" — ELM327 склеивает.
     * Формат: 49 PID [count] [ascii bytes...]. У ECU без count — сразу ascii.
     */
    fun parseMode09Ascii(raw: String, expectedPid: Int): String? {
        val cleaned = raw
            .replace(">", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("[0-9A-Fa-f]:"), " ") // префиксы строк "0:" "1:"
            .replace("SEARCHING...", "")
            .replace("NO DATA", "")
            .trim()
        val tokens = cleaned.split(Regex("\\s+"))
            .filter { it.length == 2 && it.matches(Regex("[0-9A-Fa-f]{2}")) }
            .map { it.toInt(16) }
        if (tokens.size < 3) return null
        val idx = (0 until tokens.size - 1).firstOrNull {
            tokens[it] == 0x49 && tokens[it + 1] == expectedPid
        } ?: return null
        var data = tokens.drop(idx + 2)
        // Первый байт может быть количеством элементов (для VIN — 01). Если он маленький — пропускаем.
        if (data.firstOrNull() != null && data.first() <= 8) data = data.drop(1)
        val bytes = data.map { it.toByte() }.toByteArray()
        val ascii = String(bytes, Charsets.US_ASCII)
            .filter { it.code in 32..126 }
            .trim()
        return ascii.takeIf { it.isNotBlank() }
    }

    /**
     * Mode 03: список DTC. Каждый код = 2 байта.
     * Первый nibble => prefix:  0x0=P, 0x1=C, 0x2=B, 0x3=U
     * Следующие 3 nibble => номер.
     * После "headers off" ответ начинается с 43, затем количество не указано — просто список.
     */
    fun parseDTCList(raw: String, mode: Int = 3): List<String> {
        val bytes = cleanBytes(raw)
        val payload = payload(bytes, mode, expectedPid = null)
        val data = if (payload.size % 2 != 0) payload.drop(1) else payload
        if (data.isEmpty()) return emptyList()
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 1 < data.size) {
            val a = data[i]
            val b = data[i + 1]
            i += 2
            if (a == 0 && b == 0) continue
            val prefix = when ((a shr 6) and 0x3) {
                0 -> 'P'; 1 -> 'C'; 2 -> 'B'; 3 -> 'U'; else -> 'P'
            }
            val first = (a shr 4) and 0x3
            val second = a and 0x0F
            val third = (b shr 4) and 0x0F
            val fourth = b and 0x0F
            codes += "%c%d%X%X%X".format(prefix, first, second, third, fourth)
        }
        return codes
    }

    /** Возвращает категорию DTC по первому символу. */
    fun dtcCategory(code: String): DTCCategory = DTCCategory.values().firstOrNull {
        it.prefix == code.firstOrNull()?.uppercaseChar()
    } ?: DTCCategory.POWERTRAIN

    /**
     * Парсинг битовой маски PIDS_SUPPORTED (PID 01 00).
     * Возвращает список PID (например "05", "0C", "0D").
     */
    fun parseSupportedPidsMask(raw: String, baseOffset: Int = 0): List<String> {
        val bytes = cleanBytes(raw)
        val data = payload(bytes, expectedMode = 1, expectedPid = baseOffset)
        if (data.size < 4) return emptyList()
        val out = mutableListOf<String>()
        val mask32 = ((data[0] and 0xFF) shl 24) or
                ((data[1] and 0xFF) shl 16) or
                ((data[2] and 0xFF) shl 8) or
                (data[3] and 0xFF)
        for (bit in 0 until 32) {
            // Bit 31 = PID baseOffset+1, Bit 30 = baseOffset+2, ...
            if ((mask32 ushr (31 - bit)) and 1 == 1) {
                out += "%02X".format(baseOffset + bit + 1)
            }
        }
        return out
    }

    private fun single(raw: String, expectedPid: Int): Int? {
        val data = payload(cleanBytes(raw), expectedMode = 1, expectedPid = expectedPid)
        return data.firstOrNull()
    }

    private fun pair(raw: String, expectedPid: Int): Pair<Int, Int>? {
        val data = payload(cleanBytes(raw), expectedMode = 1, expectedPid = expectedPid)
        return if (data.size >= 2) data[0] to data[1] else null
    }
}
