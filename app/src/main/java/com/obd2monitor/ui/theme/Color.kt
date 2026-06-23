package com.obd2monitor.ui.theme

import androidx.compose.ui.graphics.Color

// Базовая палитра. Красный и голубой полностью убраны — заменены на оттенки серого.
// Жёлтый/зелёный (warning/success) оставлены для понятной сигнализации состояний.
val ColorBackground = Color(0xFF0D0D0F)
val ColorSurface = Color(0xFF1B1C20)         // (был синеватый #1A1A2E) — нейтрально-тёмный
val ColorCard = Color(0xFF1E1F24)            // (был тёмно-синий #16213E) — нейтрально-тёмный фон карточек
val ColorPrimary = Color(0xFFC8CCD4)         // (был красный #E94560) — светлый нейтрально-серый
val ColorSecondary = Color(0xFF2A2D33)
val ColorAccent = Color(0xFF8E96A6)          // (был голубой #00D4FF) — прохладный серый, чуть темнее Primary
val ColorSuccess = Color(0xFF00E676)
val ColorWarning = Color(0xFFFFD600)
val ColorError = Color(0xFFD49A4D)           // (был красный #FF5252) — тёплый янтарь
val ColorTextPrimary = Color(0xFFFFFFFF)
val ColorTextSecondary = Color(0xFF8A8A9A)
// Цвет текста/иконок ПОВЕРХ светлой плашки (ColorPrimary). Тёмный, чтобы белое не сливалось.
val ColorOnPrimary = Color(0xFF15171C)

// Нейтральные оттенки серого — для иконок карточек, индикаторов нав-бара
val ColorNeutral = Color(0xFFB8BCC8)         // светло-серый текст/иконка
val ColorNeutralDim = Color(0xFF6E7280)      // приглушенный серый
val ColorNeutralSurface = Color(0xFF25262C)  // (был синеватый #22293B) — фон карточки чуть светлее
val ColorCardGray = Color(0xFF24262C)        // плоский серый фон data-карточек
val ColorIndicator = Color(0x33FFFFFF)       // полупрозрачный индикатор активного таба
val ColorNavPill = Color(0xCC15171C)         // полупрозрачный «стеклянный» фон плавающей пилюли нав-бара
val ColorNavBorder = Color(0x22FFFFFF)       // тонкая граница пилюли

// Полупрозрачные оттенки для glassmorphism
val ColorGlass = Color(0x33FFFFFF)
val ColorGlassBorder = Color(0x55FFFFFF)

// Фон модальных окон (AlertDialog/диалоги добавления записей).
// Должен быть ОТЧЁТЛИВО темнее обычного surface, иначе диалог зрительно
// сливается с экраном за ним: дефолтный M3 surfaceContainerHigh (~#2B2D33)
// почти как ColorNeutralSurface, и диалог выглядел бы «прозрачным».
val ColorDialogScrim = Color(0xFF15161A)
