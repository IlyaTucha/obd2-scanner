package com.obd2monitor.ui.theme

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = ColorPrimary,
    onPrimary = ColorOnPrimary,        // ← тёмный текст ПОВЕРХ светло-серой Primary-плашки
    secondary = ColorAccent,
    onSecondary = ColorOnPrimary,      // ← тоже тёмный, ColorAccent — средне-серый
    tertiary = ColorSuccess,
    background = ColorBackground,
    onBackground = ColorTextPrimary,
    surface = ColorSurface,
    onSurface = ColorTextPrimary,
    surfaceVariant = ColorCard,
    onSurfaceVariant = ColorTextPrimary,
    error = ColorError,
    onError = ColorOnPrimary,
    outline = ColorTextSecondary,
)

@Composable
fun OBD2Theme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = ColorBackground.toArgb()
            window.navigationBarColor = ColorBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
    ) {
        // Surface задаёт LocalContentColor = onBackground (белый) для всего дерева,
        // иначе Text без явного color рисуется чёрным на тёмном фоне.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = ColorBackground,
            contentColor = ColorTextPrimary,
            content = content,
        )
    }
}
