package com.obd2monitor.data.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBtManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Низкоуровневый Bluetooth Classic SPP-сокет к ELM327.
 * SPP UUID = 00001101-0000-1000-8000-00805F9B34FB.
 *
 * Все методы — suspend, выполняются на IO-диспатчере.
 * Чтение строк — терминатор '>' (ELM327 prompt).
 */
@Singleton
class BluetoothObdConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PROMPT = '>'
        const val DEFAULT_READ_TIMEOUT_MS = 5000L

        /** Длинный таймаут для команд, инициирующих сессию с ECU (slow init может длиться до 10 c). */
        const val LONG_READ_TIMEOUT_MS = 12000L
    }

    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBtManager)?.adapter
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    fun adapter(): BluetoothAdapter? = adapter

    /** Список спаренных устройств. Требует BLUETOOTH_CONNECT (S+). */
    fun pairedDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission()) return emptyList()
        return adapter?.bondedDevices?.toList().orEmpty()
    }

    private fun hasConnectPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Throws(SecurityException::class, IOException::class)
    suspend fun connect(deviceAddress: String) = withContext(Dispatchers.IO) {
        val a = adapter ?: throw IOException("Bluetooth adapter недоступен")
        if (!a.isEnabled) throw IOException("Bluetooth выключен")
        if (!hasConnectPermission()) throw SecurityException("Нет разрешения BLUETOOTH_CONNECT")
        a.cancelDiscovery()
        val device = a.getRemoteDevice(deviceAddress)
        // Создаём insecure RFCOMM сокет — ELM327 редко требует pairing-key.
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            s.connect()
        } catch (e: IOException) {
            // Fallback к insecure варианту
            try { s.close() } catch (_: IOException) {}
            val s2 = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            s2.connect()
            socket = s2
            input = s2.inputStream
            output = s2.outputStream
            warmupAfterConnect()
            return@withContext
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
        warmupAfterConnect()
    }

    /**
     * После открытия RFCOMM-сокета дешёвые ELM v1.5 клоны до 1.5 секунд шлют
     * мусорные байты (артефакты bluetooth-стека или неполный stale-prompt).
     * Если начать сразу слать ATZ — первые байты команды теряются, ATZ уходит
     * как "TZ" → ELM отвечает "?" → всё последующее идёт вразнос (echo не
     * выключается, и 0100 возвращает «0100..OK»).
     */
    private fun warmupAfterConnect() {
        try { Thread.sleep(1200) } catch (_: InterruptedException) {}
        val inp = input ?: return
        try { while (inp.available() > 0) inp.read() } catch (_: IOException) {}
    }

    @Throws(IOException::class)
    suspend fun sendRaw(command: String, timeoutMs: Long = DEFAULT_READ_TIMEOUT_MS): String = withContext(Dispatchers.IO) {
        val out = output ?: throw IOException("Не подключен")
        val inp = input ?: throw IOException("Не подключен")
        // Сливаем «хвост» от предыдущей команды (если ELM ответил позже таймаута,
        // или прислал доп. данные после prompt'а). Иначе клиент рассинхронизируется,
        // и ELM начнёт отвечать «STOPPED» на новые команды.
        try { while (inp.available() > 0) inp.read() } catch (_: IOException) {}
        val payload = (command + "\r").toByteArray(Charsets.US_ASCII)
        out.write(payload)
        out.flush()
        val raw = readUntilPrompt(inp, timeoutMs)
        stripEcho(command, raw)
    }

    /**
     * Срезает echo команды из ответа: на ELM v1.5 клонах ATE0 иногда не
     * срабатывает с первого раза (мусор в буфере, потерянные байты), и тогда
     * адаптер возвращает «0100\r..OK\r>» вместо «41 00 …». Без снятия echo
     * парсер видит [0x01, 0x00] вместо [0x41, 0x00, …] и считает что ECU молчит.
     *
     * ВАЖНО: если после среза ничего не остаётся (адаптер вернул только echo —
     * значит echo on И ECU вообще не ответил) — возвращаем оригинал, чтобы
     * диагностика смогла показать пользователю «вот что ELM прислал». Иначе
     * диагностика получала бы пустую строку и юзер видел совет без контекста.
     */
    private fun stripEcho(command: String, raw: String): String {
        val s = raw.trimStart('\r', '\n', ' ', '\t')
        if (s.startsWith(command, ignoreCase = true)) {
            val stripped = s.substring(command.length).trimStart('\r', '\n', ' ', '\t').trim()
            if (stripped.isNotBlank()) return stripped
        }
        return raw.trim()
    }

    @Throws(IOException::class)
    private fun readUntilPrompt(inp: InputStream, timeoutMs: Long): String {
        val sb = StringBuilder(64)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) {
                val b = inp.read()
                if (b == -1) break
                val ch = b.toChar()
                if (ch == PROMPT) return sb.toString().trim()
                sb.append(ch)
            } else {
                // 4 ms — компромисс между откликом и нагрузкой на CPU. При 15 ms и
                // 17 PID/snapshot набегало бы ~250 ms на одни тики ожидания, плюс ответы
                // ELM — итого > 1 сек на снимок и заметная задержка по нажатию газа.
                Thread.sleep(4)
            }
        }
        return sb.toString().trim()
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        try { input?.close() } catch (_: IOException) {}
        try { output?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        input = null; output = null; socket = null
    }
}
