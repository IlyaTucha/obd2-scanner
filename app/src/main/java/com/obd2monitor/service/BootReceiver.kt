package com.obd2monitor.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Поднимает процесс после перезагрузки телефона и после обновления приложения.
 *
 * Иначе поездка, стоявшая на паузе (вышли из машины, телефон перезагрузился),
 * оставалась без записи до тех пор, пока приложение не откроют вручную: к адаптеру
 * подключалась только MainActivity, и дальнейшая дорога терялась. Сама работа — в
 * AutoSessionManager.start() (его зовёт и OBDApplication; повторный вызов — no-op):
 * усыновление незакрытой поездки, запуск сервиса и подключение к адаптеру. Эти
 * broadcast'ы — одно из исключений, когда Android разрешает старт foreground-сервиса
 * из фона.
 *
 * В манифесте приёмник выключен: его включает AutoSessionManager, пока есть открытая
 * поездка (см. [setEnabled]). Без поездки система приложение после загрузки не будит.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var autoSession: AutoSessionManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> autoSession.start()
        }
    }

    companion object {
        /** Состояние компонента система хранит сама — переживает и перезагрузку,
         *  и обновление приложения. */
        fun setEnabled(context: Context, enabled: Boolean) {
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, BootReceiver::class.java),
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
