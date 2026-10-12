package com.obd2monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.obd2monitor.ui.theme.ColorTextSecondary

/** Максимальная ширина контента: в горизонтали и на планшете карточки не
 *  растягиваются на весь экран, а стоят колонкой по центру. */
val MaxContentWidth = 840.dp

/** Отступ снизу под плавающую панель вкладок. */
val BottomBarPadding = 96.dp

/**
 * Высота карты: [preferred], но не больше половины высоты экрана. Карта забирает
 * касания себе (перемещение одним пальцем), поэтому в горизонтали она не должна
 * занимать весь экран — иначе страницу нечем прокрутить.
 */
@Composable
fun mapHeight(preferred: Dp): Dp = minOf(preferred, (LocalConfiguration.current.screenHeightDp * 0.5f).dp)

/** Широкая раскладка — горизонтальная ориентация телефона или планшет. */
@Composable
fun isWideLayout(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/**
 * Прокручиваемая колонка экрана: фон, ограничение ширины по центру, стандартные
 * отступы. [bottomPadding] — место под панель вкладок (или 24 dp на вложенных экранах).
 */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = BottomBarPadding,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            content()
            Spacer(Modifier.height(bottomPadding))
        }
    }
}

/**
 * Сетка карточек: число колонок подбирается по ширине (на телефоне в портрете — 2,
 * в горизонтали — 3–5). Карточки в строке выравниваются по высоте.
 */
@Composable
fun <T> AdaptiveGrid(
    items: List<T>,
    modifier: Modifier = Modifier,
    minCellWidth: Dp = 160.dp,
    spacing: Dp = 10.dp,
    cell: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = ((maxWidth + spacing) / (minCellWidth + spacing)).toInt().coerceAtLeast(2)
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    row.forEach { cell(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Небольшой заголовок группы карточек. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = ColorTextSecondary,
        modifier = modifier.padding(top = 4.dp, start = 2.dp),
    )
}
