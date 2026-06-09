package com.obd2monitor.ui.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.obd2monitor.ui.theme.ColorTextSecondary
import kotlinx.coroutines.launch

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

    NavHost(
        navController = navController,
        startDestination = Routes.TABS,
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
        composable(
            Routes.TRIP_HISTORY,
            // Быстрая анимация — 150 мс slide справа + fade. Дефолтные 700 мс
            // ощущаются «тяжело» для частого перехода между Сессией и Историей.
            enterTransition = {
                slideInHorizontally(animationSpec = tween(150)) { it } + fadeIn(tween(150))
            },
            exitTransition = {
                slideOutHorizontally(animationSpec = tween(150)) { it } + fadeOut(tween(150))
            },
            popEnterTransition = {
                slideInHorizontally(animationSpec = tween(150)) { -it / 4 } + fadeIn(tween(150))
            },
            popExitTransition = {
                slideOutHorizontally(animationSpec = tween(150)) { it } + fadeOut(tween(150))
            },
        ) {
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

    fun goToTab(tab: NavTab) {
        scope.launch { pagerState.scrollToPage(tab.page) }
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
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    PAGE_DASHBOARD -> DashboardScreen(
                        onOpenSettings = { goToPage(PAGE_SETTINGS) },
                        onOpenDetail = onOpenDetail,
                    )
                    PAGE_TRIP -> TripActiveScreen(onOpenHistory = onOpenHistory)
                    PAGE_DIAGNOSTICS -> DiagnosticsScreen()
                    PAGE_SETTINGS -> SettingsScreen(
                        showBackupMenu = showBackupMenu,
                        onDismissBackupMenu = { showBackupMenu = false },
                    )
                }
            }
        }
        FloatingTabBar(
            currentPage = pagerState.currentPage,
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
                .padding(horizontal = 14.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun FloatingTabBar(
    currentPage: Int,
    onTabClick: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(ColorNavPill)
            .border(BorderStroke(1.dp, ColorNavBorder), RoundedCornerShape(28.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            BottomTabItem(
                tab = tab,
                selected = currentPage == tab.page,
                onClick = { onTabClick(tab) },
            )
        }
    }
}

@Composable
private fun BottomTabItem(
    tab: NavTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.onBackground else ColorTextSecondary
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) ColorIndicator else Color.Transparent)
            .clickable(onClick = onClick)
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
