package com.musicconvert

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicconvert.data.AppSettings
import com.musicconvert.ui.AppRoot
import com.musicconvert.ui.agreement.AgreementScreen
import com.musicconvert.ui.agreement.AgreementStore
import com.musicconvert.ui.theme.MusicConvertTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val container = (context.applicationContext as MusicConvertApp).container
            val settings by container.settingsStore.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            // SharedPreferences 同步读，首帧就能决定显示协议还是主界面，不会闪
            val agreement = remember { AgreementStore(context) }
            var accepted by remember { mutableStateOf(agreement.accepted) }

            MusicConvertTheme(dynamicColor = settings.dynamicColor) {
                if (accepted) {
                    AppRoot()
                } else {
                    AgreementScreen(
                        onAccept = {
                            agreement.accept()
                            accepted = true
                        },
                        onDecline = { finishAndRemoveTask() },
                    )
                }
            }
        }
    }
}
