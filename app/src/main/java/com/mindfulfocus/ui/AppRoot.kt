package com.mindfulfocus.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mindfulfocus.MindFocusApp
import com.mindfulfocus.audio.EasterEggPlayer
import com.mindfulfocus.ui.bell.BellScreen
import com.mindfulfocus.ui.bell.BellViewModel
import com.mindfulfocus.ui.history.HistoryScreen
import com.mindfulfocus.ui.history.HistoryViewModel
import com.mindfulfocus.ui.pomodoro.PomodoroScreen
import com.mindfulfocus.ui.pomodoro.PomodoroViewModel

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Dest("pomodoro", "番茄钟", Icons.Filled.Timer),
    Dest("bell", "正念铃", Icons.Filled.NotificationsActive),
    Dest("history", "统计", Icons.Filled.BarChart)
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    // 彩蛋：切页面就停；退到后台也停。
    LaunchedEffect(currentDest?.route) { EasterEggPlayer.stop() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE) {
                EasterEggPlayer.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            EasterEggPlayer.stop()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                destinations.forEach { dest ->
                    val selected = currentDest?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "pomodoro",
            modifier = Modifier
                .padding(innerPadding)
                // 点屏幕别处 => 停止彩蛋音效（刚点开的那一下有宽限期，不会自杀）。
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { EasterEggPlayer.stopFromElsewhere() })
                }
        ) {
            composable("pomodoro") {
                val vm: PomodoroViewModel = viewModel(factory = AppViewModelProviders.pomodoroFactory())
                PomodoroScreen(vm)
            }
            composable("bell") {
                val vm: BellViewModel = viewModel(factory = AppViewModelProviders.bellFactory())
                BellScreen(vm)
            }
            composable("history") {
                val vm: HistoryViewModel = viewModel(factory = AppViewModelProviders.historyFactory())
                HistoryScreen(vm)
            }
        }
    }
}
