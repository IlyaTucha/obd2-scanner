package com.obd2monitor.data.obd

import com.obd2monitor.domain.model.MonitorStatus
import com.obd2monitor.domain.model.ReadinessMonitor

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
            // Префиксы строк многокадрового CAN-ответа ("0:", "1:" … "F:").
            .replace(Regex("(^|\\s)[0-9A-Fa-f]:"), " ")
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

    /** PID 01 2F: A * 100 / 255 (%) */
    fun parseFuelLevel(raw: String): Float? = single(raw, 0x2F)?.let { it * 100f / 255f }

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
     * Mode 03 / 07 / 0A: список DTC. Каждый код — 2 байта: старшие 2 бита — буква
     * (P/C/B/U), дальше 14 бит — цифры кода.
     *
     * Форматы ответа после ATH0/ATS0:
     *  - K-line / J1850: строки «43 AABB CCDD EEFF» по 3 кода, дополненные нулями;
     *    при большем числе кодов приходит несколько строк, каждая с «43»;
     *  - CAN, один кадр: «43 NN AABB …», где NN — число кодов;
     *  - CAN, много кадров: «00A» (длина в байтах), затем «0:43 NN …», «1:…»;
     *  - несколько блоков управления отвечают каждый своей строкой.
     * Раньше всё это склеивалось в один поток байтов: при двух ответивших блоках или
     * многокадровом ответе коды терялись или появлялись несуществующие.
     */
    fun parseDTCList(raw: String, mode: Int = 3): List<String> {
        val response = mode + 0x40
        val codes = LinkedHashSet<String>()
        for (frame in dtcFrames(raw)) {
            val start = frame.indexOf(response)
            if (start < 0) continue
            var data = frame.drop(start + 1)
            // CAN: первый байт — число кодов, данных всегда нечётное число байт.
            if (data.size % 2 == 1) {
                val count = data.first()
                data = data.drop(1).take(count * 2)
            }
            var i = 0
            while (i + 1 < data.size) {
                val a = data[i]
                val b = data[i + 1]
                i += 2
                if (a == 0 && b == 0) continue
                codes += decodeDtc(a, b)
            }
        }
        return codes.toList()
    }

    /** Два байта DTC → «P0133». */
    fun decodeDtc(a: Int, b: Int): String {
        val prefix = "PCBU"[(a shr 6) and 0x3]
        return "%c%d%X%X%X".format(prefix, (a shr 4) and 0x3, a and 0x0F, (b shr 4) and 0x0F, b and 0x0F)
    }

    /**
     * Разбивает ответ на логические кадры: обычная строка — отдельный кадр; строки
     * многокадрового CAN-ответа («0:», «1:» после строки с длиной) склеиваются в один
     * и обрезаются до объявленной длины (хвост кадра дополняется мусором 00/AA/55).
     */
    private fun dtcFrames(raw: String): List<List<Int>> {
        val lines = raw.replace(">", "").split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val frames = mutableListOf<List<Int>>()
        var multi: MutableList<Int>? = null
        var multiLength = 0
        fun flush() {
            multi?.let { frames += it.take(multiLength) }
            multi = null
        }
        for (line in lines) {
            val header = Regex("^([0-9A-Fa-f]{3})$").find(line.replace(" ", ""))
            val cont = Regex("^([0-9A-Fa-f]):(.*)$").find(line)
            when {
                header != null -> {
                    flush()
                    multi = mutableListOf()
                    multiLength = header.groupValues[1].toInt(16)
                }
                cont != null && multi != null -> multi!! += cleanBytes(cont.groupValues[2])
                else -> {
                    flush()
                    val bytes = cleanBytes(line)
                    if (bytes.isNotEmpty()) frames += bytes
                }
            }
        }
        flush()
        return frames
    }

    /**
     * PID 01 01: статус лампы Check Engine, число сохранённых кодов и готовность
     * мониторов самодиагностики (SAE J1979). Null — ЭБУ не ответил.
     */
    fun parseMonitorStatus(raw: String): MonitorStatus? {
        val d = payload(cleanBytes(raw), expectedMode = 1, expectedPid = 0x01)
        if (d.size < 4) return null
        val (a, b, c, e) = d
        val diesel = (b and 0x08) != 0
        val monitors = mutableListOf<ReadinessMonitor>()
        fun add(name: String, availByte: Int, availBit: Int, incByte: Int, incBit: Int) {
            if ((availByte shr availBit) and 1 == 1) {
                monitors += ReadinessMonitor(name, complete = (incByte shr incBit) and 1 == 0)
            }
        }
        add("Пропуски зажигания", b, 0, b, 4)
        add("Топливная система", b, 1, b, 5)
        add("Компоненты", b, 2, b, 6)
        val names = if (diesel) listOf(
            "Катализатор NMHC", "Нейтрализатор NOx/SCR", null, "Давление наддува",
            null, "Датчик отработавших газов", "Сажевый фильтр", "EGR / VVT",
        ) else listOf(
            "Катализатор", "Подогрев катализатора", "Система EVAP", "Вторичный воздух",
            null, "Кислородные датчики", "Подогрев кислородных датчиков", "EGR / VVT",
        )
        names.forEachIndexed { bit, name -> if (name != null) add(name, c, bit, e, bit) }
        return MonitorStatus(
            milOn = (a and 0x80) != 0,
            storedDtcCount = a and 0x7F,
            diesel = diesel,
            monitors = monitors,
        )
    }

    /** Mode 02 PID 02: код, из-за которого сохранён стоп-кадр. Null — стоп-кадра нет. */
    fun parseFreezeFrameDtc(raw: String): String? {
        val d = freezePayload(raw, 0x02) ?: return null
        if (d.size < 2 || (d[0] == 0 && d[1] == 0)) return null
        return decodeDtc(d[0], d[1])
    }

    /** Данные стоп-кадра (Mode 02, кадр 0): «42 PID 00 данные…» → данные. */
    fun freezePayload(raw: String, pid: Int): List<Int>? {
        val bytes = cleanBytes(raw)
        val idx = (0 until bytes.size - 2).firstOrNull { bytes[it] == 0x42 && bytes[it + 1] == pid } ?: return null
        // Кадр (обычно 00) идёт после PID; на части ECU его нет в ответе.
        val rest = bytes.drop(idx + 2)
        return if (rest.size > 1 && rest[0] == 0) rest.drop(1) else rest
    }

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
