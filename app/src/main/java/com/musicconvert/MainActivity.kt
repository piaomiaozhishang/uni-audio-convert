package com.musicconvert

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicconvert.data.AppSettings
import com.musicconvert.ui.AppRoot
import com.musicconvert.ui.theme.MusicConvertTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val store = (context.applicationContext as MusicConvertApp).container.settingsStore
            val settings by store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            MusicConvertTheme(dynamicColor = settings.dynamicColor) {
                AppRoot()
            }
        }
    }
}
