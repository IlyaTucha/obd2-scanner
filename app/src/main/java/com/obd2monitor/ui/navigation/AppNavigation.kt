package com.obd2monitor.ui.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.obd2monitor.ui.screen.dashboard.DashboardScreen
import com.obd2monitor.ui.screen.dashboard.DashboardViewModel
import com.obd2monitor.ui.screen.dashboard.ParameterDetailScreen
import com.obd2monitor.ui.screen.diagnostics.DiagnosticsScreen
import com.obd2monitor.ui.screen.settings.SettingsScreen
import com.obd2monitor.ui.screen.trip.TripActiveScreen
import com.obd2monitor.ui.screen.trip.TripHistoryScreen
import com.obd2monitor.ui.screen.trip.TripParameterDetailScreen
import com.obd2monitor.ui.screen.trip.TripSummaryScreen
import com.obd2monitor.ui.theme.ColorIndicator
import com.obd2monitor.ui.theme.ColorNavBorder
import com.obd2monitor.ui.theme.ColorNavPill
import com.obd2monitor.ui.theme.ColorTextPrimary
import com.obd2monitor.ui.theme.ColorTextSecondary
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

object Routes {
    const val TABS = "tabs"
    const val DETAIL = "detail/{key}"
    const val TRIP_SUMMARY = "trip_summary/{tripId}"
    const val TRIP_HISTORY = "trip_history"
    const val TRIP_DETAIL = "trip_detail/{tripId}/{key}"
    fun detail(key: String) = "detail/$key"
    fun tripSummary(tripId: String) = "trip_summary/$tripId"
    fun tripDetail(tripId: String, key: String) = "trip_detail/$tripId/$key"
}

private data class NavTab(val key: String, val label: String, val icon: ImageVector, val page: Int)

// 4 страницы в HorizontalPager: Главная(0) → Сессия(1) → Ошибки(2) → Настройки(3).
// История доступна как отдельный nav-route (Routes.TRIP_HISTORY) — открывается
// кнопкой со страницы «Сессия», возврат через back. «Авто» вынесена в нижнюю
// часть экрана «Настройки» (CarSection), отдельной вкладки больше нет.
private val tabs = listOf(
    NavTab("dashboard", "Главная", Icons.Filled.Speed, 0),
    NavTab("trip", "Сессия", Icons.Filled.DirectionsCar, 1),
    NavTab("diagnostics", "Ошибки", Icons.Filled.Build, 2),
    NavTab("settings", "Настройки", Icons.Filled.Settings, 3),
)

private const val PAGE_DASHBOARD = 0
private const val PAGE_TRIP = 1
private const val PAGE_DIAGNOSTICS = 2
private const val PAGE_SETTINGS = 3
private const val PAGE_COUNT = 4

/** Сколько подряд тапов по табу «Настройки» открывают скрытое меню резервных
 *  копий. Первые 3 — молча, начиная с 4-го показываем «ещё 3 / 2 / 1». */
private const val SETTINGS_UNLOCK_TAPS = 6

private fun pluralTap(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "нажатие"
    n % 10 in 2..4 && (n % 100 !in 12..14) -> "нажатия"
    else -> "нажатий"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    // Новый экран быстро въезжает (150 мс), а уходящий исчезает сразу. Карта
    // рисуется на отдельной GL-поверхности, которая не участвует в анимациях
    // Compose: при плавном уходе экран затухал, а карта исчезала отдельно от него.
    // Мгновенный уход убирает экран и карту одновременно.
    NavHost(
        navController = navController,
        startDestination = Routes.TABS,
        enterTransition = { slideInHorizontally(tween(150)) { it / 3 } + fadeIn(tween(150)) },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.TABS) {
            TabsContainer(
                onOpenDetail = { key -> navController.navigate(Routes.detail(key)) },
                onOpenHistory = { navController.navigate(Routes.TRIP_HISTORY) },
            )
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("key") { type = NavType.StringType }),
        ) { entry ->
            val key = entry.arguments?.getString("key") ?: "speed"
            // ВАЖНО: берём DashboardViewModel из родительского NavBackStackEntry (Routes.TABS),
            // а не создаём новый. Иначе history sparkline-метрик начинается с нуля в момент
            // открытия детального экрана — пользователь видит график «с момента открытия»,
            // а не с начала сессии.
            val parentEntry = remember(entry) { navController.getBackStackEntry(Routes.TABS) }
            val vm: DashboardViewModel = hiltViewModel(parentEntry)
            ParameterDetailScreen(
                metricKey = key,
                onBack = { navController.popBackStack() },
                viewModel = vm,
            )
        }
        composable(Routes.TRIP_HISTORY) {
            TripHistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenSummary = { id -> navController.navigate(Routes.tripSummary(id)) },
            )
        }
        composable(
            Routes.TRIP_SUMMARY,
            arguments = listOf(navArgument("tripId") { type = NavType.StringType }),
        ) { entry ->
            val tripId = entry.arguments?.getString("tripId") ?: return@composable
            TripSummaryScreen(
                onBack = { navController.popBackStack() },
                onOpenParam = { key -> navController.navigate(Routes.tripDetail(tripId, key)) },
            )
        }
        composable(
            Routes.TRIP_DETAIL,
            arguments = listOf(
                navArgument("tripId") { type = NavType.StringType },
                navArgument("key") { type = NavType.StringType },
            ),
        ) {
            TripParameterDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabsContainer(
    onOpenDetail: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = PAGE_DASHBOARD) { PAGE_COUNT }
    val scope = rememberCoroutineScope()

    fun goToPage(index: Int) {
        scope.launch { pagerState.animateScrollToPage(index) }
    }

    fun goToTab(tab: NavTab) = goToPage(tab.page)

    // При перелистывании вкладок закрываем клавиатуру и снимаем фокус с полей ввода
    // (расшифровка кода, цена топлива): иначе клавиатура оставалась открытой поверх
    // соседней вкладки.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }
    }

    val context = LocalContext.current
    var lastBackMs by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackMs < 2000) {
            (context as? Activity)?.finish()
        } else {
            lastBackMs = now
            Toast.makeText(context, "Нажмите ещё раз для выхода", Toast.LENGTH_SHORT).show()
        }
    }

    // Скрытый триггер меню резервных копий: 6 тапов подряд по табу «Настройки».
    // Первые 3 — молча (фильтр от случайных одиночных кликов). Начиная с тапа 4
    // показываем «ещё 3 / ещё 2 / ещё 1». На тапе 6 — открываем меню и сбрасываем.
    // Любой клик по другому табу обнуляет счётчик (намеренная серия = идти подряд).
    // Таймаут 4 секунды между тапами — иначе при возврате в Настройки через час
    // следующий клик случайно бы продвинул счётчик.
    var settingsTaps by remember { mutableIntStateOf(0) }
    var lastSettingsTapMs by remember { mutableLongStateOf(0L) }
    var showBackupMenu by remember { mutableStateOf(false) }
    // Текущий toast подсказки — нужен чтобы быстро тапающий юзер видел смену
    // «ещё 3 → ещё 2 → ещё 1» мгновенно, а не сидел и ждал пока три тоста
    // отыграют свои ~2 секунды по очереди. Android встроенно очередь не сбрасывает —
    // приходится самим вызывать .cancel() перед показом следующего.
    val tapToastRef = remember { mutableStateOf<Toast?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                // Соседняя страница остаётся в композиции: карта на «Сессии» не
                // пересоздаётся при каждом переходе с «Главной» и обратно.
                beyondBoundsPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    PAGE_DASHBOARD -> DashboardScreen(
                        onOpenSettings = { goToPage(PAGE_SETTINGS) },
                        onOpenDetail = onOpenDetail,
                    )
                    PAGE_TRIP -> TripActiveScreen(
                        onOpenHistory = onOpenHistory,
                        onOpenSettings = { goToPage(PAGE_SETTINGS) },
                    )
                    PAGE_DIAGNOSTICS -> DiagnosticsScreen()
                    PAGE_SETTINGS -> SettingsScreen(
                        showBackupMenu = showBackupMenu,
                        onDismissBackupMenu = { showBackupMenu = false },
                    )
                }
            }
        }
        FloatingTabBar(
            position = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
            onTabClick = { tab ->
                if (tab.page == PAGE_SETTINGS) {
                    val now = System.currentTimeMillis()
                    // Серия «оборвалась», если прошло >4 c — обнуляем счётчик и считаем
                    // этот клик первым в новой серии.
                    val tapNumber = if (now - lastSettingsTapMs > 4_000L) 1 else settingsTaps + 1
                    lastSettingsTapMs = now
                    when {
                        tapNumber >= SETTINGS_UNLOCK_TAPS -> {
                            settingsTaps = 0
                            tapToastRef.value?.cancel()
                            showBackupMenu = true
                        }
                        // С 4-го тапа подсказываем сколько осталось.
                        tapNumber > SETTINGS_UNLOCK_TAPS - 4 -> {
                            settingsTaps = tapNumber
                            val remaining = SETTINGS_UNLOCK_TAPS - tapNumber
                            // Отменяем предыдущий toast чтобы новый показался сразу,
                            // а не ждал в очереди ~2 c за каждым предыдущим.
                            tapToastRef.value?.cancel()
                            val t = Toast.makeText(
                                context,
                                "Ещё $remaining ${pluralTap(remaining)}",
                                Toast.LENGTH_SHORT,
                            )
                            tapToastRef.value = t
                            t.show()
                        }
                        else -> settingsTaps = tapNumber
                    }
                } else {
                    // Любой другой таб — серия прерывается.
                    settingsTaps = 0
                    tapToastRef.value?.cancel()
                }
                goToTab(tab)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 14.dp),
        )
    }
}

/**
 * Плавающая пилюля навигации. Подложка-«капля» не прыгает между вкладками, а
 * перетекает вслед за свайпом: [position] — дробная позиция пейджера
 * (currentPage + currentPageOffsetFraction). Передний край капли движется быстрее
 * заднего, поэтому в середине перехода она вытягивается и чуть сплющивается, а
 * у цели снова собирается в овал.
 */
@Composable
private fun FloatingTabBar(
    position: () -> Float,
    onTabClick: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Левый край и ширина каждой вкладки внутри пилюли (px).
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val pos = position().coerceIn(0f, (tabs.size - 1).toFloat())
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(ColorNavPill)
            .border(BorderStroke(1.dp, ColorNavBorder), RoundedCornerShape(28.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .drawBehind {
                val from = floor(pos).toInt().coerceIn(0, tabs.size - 1)
                val to = (from + 1).coerceAtMost(tabs.size - 1)
                val a = bounds[from] ?: return@drawBehind
                val b = bounds[to] ?: a
                val f = pos - from
                // Правый край ведёт при движении вправо (easeOut), левый догоняет
                // (easeIn); при движении влево роли меняются сами собой.
                val left = lerp(a.first, b.first, f * f)
                val right = lerp(a.first + a.second, b.first + b.second, 1f - (1f - f) * (1f - f))
                val squash = 1f - 0.14f * sin(PI.toFloat() * f)
                val h = size.height * squash
                drawRoundRect(
                    color = ColorIndicator,
                    topLeft = Offset(left, (size.height - h) / 2f),
                    size = Size(right - left, h),
                    cornerRadius = CornerRadius(h / 2f),
                )
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            // 1 — вкладка выбрана полностью, 0 — совсем нет; между ними цвет смешивается.
            val selection = (1f - abs(pos - tab.page)).coerceIn(0f, 1f)
            BottomTabItem(
                tab = tab,
                tint = lerp(ColorTextSecondary, ColorTextPrimary, selection),
                onClick = { onTabClick(tab) },
                modifier = Modifier.onPlaced {
                    bounds[tab.page] = it.positionInParent().x to it.size.width.toFloat()
                },
            )
        }
    }
}

@Composable
private fun BottomTabItem(
    tab: NavTab,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = tint)
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
