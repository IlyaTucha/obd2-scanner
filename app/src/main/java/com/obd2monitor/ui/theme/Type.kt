package com.obd2monitor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Единое семейство шрифтов по всему приложению — системный sans-serif.
// Если положить Inter/Roboto в res/font, можно заменить ниже.
val AppFont: FontFamily = FontFamily.SansSerif

val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 56.sp),
    displayMedium = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 44.sp),
    displaySmall = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 32.sp),
    headlineLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineMedium = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    headlineSmall = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)
