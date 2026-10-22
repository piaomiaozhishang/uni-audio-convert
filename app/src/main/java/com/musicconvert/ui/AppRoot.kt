package com.musicconvert.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.musicconvert.MusicConvertApp
import com.musicconvert.ui.convert.ConvertScreen
import com.musicconvert.ui.history.HistoryScreen
import com.musicconvert.ui.player.MiniPlayer
import com.musicconvert.ui.settings.SettingsScreen

@Composable
fun AppRoot() {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val player = remember { (context.applicationContext as MusicConvertApp).container.player }

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
                    NavigationBarItem(
                        selected = tabIndex == 0,
                        onClick = { tabIndex = 0 },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        label = { Text("转换") },
                    )
                    NavigationBarItem(
                        selected = tabIndex == 1,
                        onClick = { tabIndex = 1 },
                        icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                        label = { Text("历史") },
                    )
                    NavigationBarItem(
                        selected = tabIndex == 2,
                        onClick = { tabIndex = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("设置") },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = tabIndex,
                transitionSpec = {
                    // 按导航方向左右滑动：往右切新页从右边进，往左切反过来
                    val forward = targetState > initialState
                    val slideIn = slideInHorizontally(
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    ) { width -> if (forward) width / 3 else -width / 3 }
                    val slideOut = slideOutHorizontally(
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    ) { width -> if (forward) -width / 3 else width / 3 }
                    (slideIn + fadeIn(tween(200))) togetherWith (slideOut + fadeOut(tween(200)))
                },
                label = "tab",
            ) { index ->
                when (index) {
                    0 -> ConvertScreen(onRequestNotificationPermission = requestNotification)
                    1 -> HistoryScreen(onPlay = { uri, title -> player.play(uri, title) })
                    else -> SettingsScreen()
                }
            }
        }
    }
}
