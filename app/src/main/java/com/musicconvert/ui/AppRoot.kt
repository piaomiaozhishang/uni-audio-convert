package com.musicconvert.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.musicconvert.MusicConvertApp
import com.musicconvert.ui.about.AboutScreen
import com.musicconvert.ui.convert.ConvertScreen
import com.musicconvert.ui.formats.FormatsScreen
import com.musicconvert.ui.history.HistoryScreen
import com.musicconvert.ui.player.MiniPlayer
import com.musicconvert.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("转换", Icons.Default.PlayArrow),
    Tab("历史", Icons.Default.DateRange),
    Tab("关于", Icons.Default.Info),
    Tab("设置", Icons.Default.Settings),
)

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val player = remember { (context.applicationContext as MusicConvertApp).container.player }

    var formatsOpen by rememberSaveable { mutableStateOf(false) }

    if (formatsOpen) {
        BackHandler { formatsOpen = false }
        FormatsScreen(onBack = { formatsOpen = false })
        return
    }

    val pagerState = rememberPagerState(pageCount = { TABS.size })
    val scope = rememberCoroutineScope()

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val requestNotification: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                MiniPlayer()
                NavigationBar {
                    TABS.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // 用 HorizontalPager 代替 AnimatedContent：分页位移由 graphicsLayer 驱动，
        // 动画期间不会重建两个页面；预组合相邻页后手指一动就有画面。
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            beyondViewportPageCount = 1,
            key = { it },
        ) { page ->
            when (page) {
                0 -> ConvertScreen(
                    onRequestNotificationPermission = requestNotification,
                    onOpenFormats = { formatsOpen = true },
                )

                1 -> HistoryScreen(onPlay = { uri, title -> player.play(uri, title) })
                2 -> AboutScreen()
                else -> SettingsScreen()
            }
        }
    }
}
