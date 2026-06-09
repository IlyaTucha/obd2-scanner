package com.obd2monitor.data.bluetooth

import com.obd2monitor.data.obd.ELMAtCommand
import com.obd2monitor.data.obd.OBDParser
import com.obd2monitor.domain.model.OBDProtocol
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Высокоуровневая обёртка над BluetoothObdConnection: инициализация ELM327,
 * выбор протокола, чтение поддерживаемых PID. См. ТЗ §5.1.
 *
 * Минимальный init по образцу OpenDiag/Torque: ATZ → echo/headers/spaces off →
 * адаптивный таймаут → ATSP0 (auto) → 0100 с длинным таймаутом и повторами.
 * Если auto не справился — полный сброс и явный перебор K-line/CAN протоколов.
 *
 * Чем меньше «лишних» AT-команд (ATWS, ATKW0, ATCAF1, ATPC) — тем стабильнее
 * на дешёвых ELM v1.5 клонах: они часто отвечают `?` на нестандартные команды
 * и сваливаются в рассинхрон.
 */
class ELM327Protocol @Inject constructor(
    private val bt: BluetoothObdConnection,
) {
    suspend fun initialize(preferredProtocol: OBDProtocol? = null): InitResult {
        // Этап 1: стандартный init + AUTO. Так делают все рабочие приложения.
        var version = ""
        runCatching { resetAdapter() }.onSuccess { version = it }

        var pids = trySupportedPids(OBDProtocol.AUTO, longTimeout = true)
        var chosen = OBDProtocol.AUTO
        var lastRaw = pids.second

        // Этап 2: AUTO не сработал → полный сброс и явный перебор протоколов.
        // Порядок (как делают Car Scanner / OpenDiag):
        //   1) предпочитаемый из профиля авто (если задан)
        //   2) CAN 11/500 — самый частый протокол на машинах 2008+ (Hyundai Accent MC
        //      европейской/американской спецификации уже на CAN-шине)
        //   3) CAN 29/500 — расширенные адреса
        //   4) K-line: KWP fast → ISO 9141-2 → KWP slow (для корейской/российской спеки)
        // CAN-попытка занимает <1 c (нет slow-init), поэтому проверять её первой
        // дешевле, чем ждать 5-6 c на каждой K-line попытке.
        if (pids.first.isEmpty()) {
            val attempts = mutableListOf<OBDProtocol>()
            preferredProtocol?.let { if (it != OBDProtocol.AUTO && it !in attempts) attempts += it }
            listOf(
                OBDProtocol.ISO_15765_4_CAN_11_500,
                OBDProtocol.ISO_15765_4_CAN_29_500,
                OBDProtocol.ISO_14230_4_KWP_FAST,
                OBDProtocol.ISO_9141_2,
                OBDProtocol.ISO_14230_4_KWP_5BAUD,
            ).forEach { p -> if (p !in attempts) attempts += p }

            for (proto in attempts) {
                // Полный сброс перед каждой попыткой — это надёжнее чем ATPC, потому что
                // на рассинхроне (после STOPPED/CAN ERROR) ELM иногда уже не выходит из «битого»
                // состояния без ATZ.
                runCatching { resetAdapter() }
                val (got, raw) = trySupportedPids(proto, longTimeout = false)
                if (raw.isNotBlank()) lastRaw = raw
                if (got.isNotEmpty()) {
                    pids = got to raw
                    chosen = proto
                    break
                }
            }
        }

        // Этап 3: на AUTO определяем какой протокол реально выбрался.
        val detected = if (chosen == OBDProtocol.AUTO && pids.first.isNotEmpty()) {
            val dpn = runCatching { bt.sendRaw(ELMAtCommand.DESCRIBE_PROTOCOL) }
                .getOrDefault("")
                .replace(">", "")
                .trim()
            decodeProtocolNumber(dpn) ?: OBDProtocol.AUTO
        } else {
            chosen
        }

        return InitResult(
            version = version,
            protocol = detected,
            supportedPids = pids.first,
            lastRawResponse = if (pids.second.isNotBlank()) pids.second else lastRaw,
        )
    }

    /**
     * Выполняет ATZ + базовый init. Возвращает версию ELM, либо "" если не определилась.
     * Вынесено отдельно, чтобы можно было повторить для каждого протокола.
     *
     * Известный баг ELM v1.5 клонов: первая команда после открытия SPP-сокета
     * часто теряет байты, и ATE0 не доходит. В результате echo остаётся ON,
     * и любой 0100 возвращает «0100 ..OK» (это и есть ваш кейс). Лечим
     * повторным ATE0 с верификацией ответа на отдельной короткой команде.
     */
    private suspend fun resetAdapter(): String {
        // 1. ATZ — повторяем до 2 раз, между попытками длинная пауза (boot ELM ~2 c)
        repeat(2) {
            runCatching { bt.sendRaw(ELMAtCommand.RESET, timeoutMs = 5000) }
            delay(1500)
        }

        // 2. Echo OFF — критично. Повторяем до 4 раз пока не получим явное OK.
        // Параллельно дёргаем ATL0/ATS0 — они тоже подтверждают что adapter принимает команды.
        var echoOk = false
        for (i in 0 until 4) {
            val r = runCatching { bt.sendRaw(ELMAtCommand.ECHO_OFF, timeoutMs = 2000) }
                .getOrDefault("")
                .replace(">", "")
                .trim()
            if (r.equals("OK", ignoreCase = true) || r.endsWith("OK", ignoreCase = true)) {
                echoOk = true
                break
            }
            delay(250)
        }

        // 3. Базовая настройка — без них парсинг невозможен.
        bt.sendRaw(ELMAtCommand.LINEFEEDS_OFF)
        bt.sendRaw(ELMAtCommand.HEADERS_OFF)
        bt.sendRaw(ELMAtCommand.SPACES_OFF)
        bt.sendRaw(ELMAtCommand.ADAPTIVE_TIMING)
        bt.sendRaw("ATSTFF")

        // 4. Если ATE0 так и не подтвердился — пробуем ещё один заход через ATWS (warm start).
        // На некоторых клонах помогает там, где ATZ оставлял мусор в буфере.
        if (!echoOk) {
            runCatching { bt.sendRaw("ATWS", timeoutMs = 3000) }
            delay(800)
            runCatching { bt.sendRaw(ELMAtCommand.ECHO_OFF, timeoutMs = 2000) }
            bt.sendRaw(ELMAtCommand.LINEFEEDS_OFF)
            bt.sendRaw(ELMAtCommand.HEADERS_OFF)
            bt.sendRaw(ELMAtCommand.SPACES_OFF)
        }

        // ATI должен вернуть «ELM327 v1.5» (или похожее). Если адаптер ответил
        // STOPPED/ERROR/?/OK без префикса ELM — это значит ATI прилетел в момент
        // bus-error. В таком случае возвращаем пустую версию вместо мусора, чтобы
        // в UI не было «Адаптер ELM327: STOPPED».
        val raw = runCatching { bt.sendRaw(ELMAtCommand.VERSION) }
            .getOrDefault("")
            .replace(">", "")
            .trim()
        return if (raw.contains("ELM", ignoreCase = true)) raw else ""
    }

    /**
     * Выставляет протокол и пытается получить supported PIDs. Возвращает (pids, lastRaw).
     * При longTimeout=true (для AUTO) — 30 c таймаут, 4 попытки. Иначе — 15 c, 3 попытки.
     *
     * Подсмотрено из Car Scanner / OpenDiag:
     *   - после ATSP* делаем «холостой» 0100 — он будит шину и даёт ELM время на
     *     auto-detect и slow-init. Ответ этого 0100 выкидываем (там может быть
     *     SEARCHING / OK / partial data — нам важно только что после него
     *     протокол залочен).
     *   - для K-line протоколов перед холостым 0100 явно дёргаем ATSI (Slow Init)
     *     или ATFI (Fast Init) — это сокращает время первого ответа и часто
     *     решает «SEARCHING до таймаута».
     *   - реальный 0100 идёт уже на «прогретую» шину — ECU отвечает почти всегда
     *     с первой попытки.
     */
    private suspend fun trySupportedPids(
        proto: OBDProtocol,
        longTimeout: Boolean,
    ): Pair<List<String>, String> {
        runCatching { bt.sendRaw(proto.atCommand) }
        delay(500)

        // K-line специфика: явно триггерим init вместо ожидания авто-инициации.
        when (proto) {
            OBDProtocol.ISO_14230_4_KWP_FAST -> {
                runCatching { bt.sendRaw("ATFI", timeoutMs = 6000) }
                delay(300)
            }
            OBDProtocol.ISO_9141_2,
            OBDProtocol.ISO_14230_4_KWP_5BAUD -> {
                runCatching { bt.sendRaw("ATSI", timeoutMs = 8000) }
                delay(500)
            }
            else -> Unit
        }

        // Холостой 0100 — будим шину. Ответ игнорируем, кроме случая когда он
        // СРАЗУ дал валидные PID (тогда возвращаем не глядя).
        runCatching { bt.sendRaw("0100", timeoutMs = if (longTimeout) 12_000L else 8_000L) }
            .getOrDefault("")
            .let { warmup ->
                val pids = OBDParser.parseSupportedPidsMask(warmup, baseOffset = 0)
                if (pids.isNotEmpty()) return pids to warmup
            }
        delay(400)

        val timeout = if (longTimeout) 30_000L else 15_000L
        val attempts = if (longTimeout) 4 else 3
        var lastRaw = ""
        for (i in 0 until attempts) {
            val raw = runCatching { bt.sendRaw("0100", timeoutMs = timeout) }.getOrDefault("")
            if (raw.isNotBlank()) lastRaw = raw
            val pids = OBDParser.parseSupportedPidsMask(raw, baseOffset = 0)
            if (pids.isNotEmpty()) return pids to raw
            // На UNABLE TO CONNECT / CAN ERROR / NO DATA нет смысла повторять — этот протокол не подходит.
            if (raw.contains("UNABLE", true) || raw.contains("CAN ERROR", true) ||
                raw.contains("NO DATA", true)) {
                break
            }
            // SEARCHING без байт — ELM ещё ищет протокол. Пауза покороче, повтор быстрее.
            // STOPPED / BUS INIT / прочее — даём ELM подышать.
            val nextDelay = if (raw.contains("SEARCHING", true)) 600L else 1500L
            if (i < attempts - 1) delay(nextDelay)
        }
        return emptyList<String>() to lastRaw
    }

    private fun decodeProtocolNumber(raw: String): OBDProtocol? {
        val hex = raw.trim().removePrefix("A").firstOrNull() ?: return null
        return when (hex) {
            '1' -> OBDProtocol.SAE_J1850_PWM
            '2' -> OBDProtocol.SAE_J1850_VPW
            '3' -> OBDProtocol.ISO_9141_2
            '4' -> OBDProtocol.ISO_14230_4_KWP_5BAUD
            '5' -> OBDProtocol.ISO_14230_4_KWP_FAST
            '6' -> OBDProtocol.ISO_15765_4_CAN_11_500
            '7' -> OBDProtocol.ISO_15765_4_CAN_29_500
            '8' -> OBDProtocol.ISO_15765_4_CAN_11_250
            '9' -> OBDProtocol.ISO_15765_4_CAN_29_250
            else -> null
        }
    }

    data class InitResult(
        val version: String,
        val protocol: OBDProtocol,
        val supportedPids: List<String>,
        val lastRawResponse: String = "",
    )
}
