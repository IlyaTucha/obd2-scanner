package com.obd2monitor

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.obd2monitor.data.preferences.UserPreferences
import com.obd2monitor.domain.repository.OBDRepository
import com.obd2monitor.ui.navigation.AppNavigation
import com.obd2monitor.ui.theme.OBD2Theme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: UserPreferences
    @Inject lateinit var obdRepo: OBDRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* пользователь ответил — работаем с тем, что дали */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent {
            OBD2Theme { AppNavigation() }
        }
        lifecycleScope.launch { attemptAutoConnect() }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        perms += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private suspend fun attemptAutoConnect() {
        val auto = prefs.autoConnect.first()
        val addr = prefs.lastDeviceAddress.first()
        if (auto && !addr.isNullOrBlank()) {
            runCatching { obdRepo.connect(addr) }
        }
    }
}
