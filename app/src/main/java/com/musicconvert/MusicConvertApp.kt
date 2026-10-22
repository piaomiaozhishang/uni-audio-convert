package com.musicconvert

import android.app.Application
import android.content.Context
import com.musicconvert.data.HistoryStore
import com.musicconvert.data.QueueStore
import com.musicconvert.data.SettingsStore
import com.musicconvert.decrypt.EkeyStore
import com.musicconvert.player.PlayerHolder
import com.musicconvert.service.ConvertEngine

class AppContainer(context: Context) {
    val historyStore = HistoryStore(context)
    val settingsStore = SettingsStore(context)
    val ekeyStore = EkeyStore(context)
    val queueStore = QueueStore(context)
    val engine = ConvertEngine(context, historyStore, settingsStore, ekeyStore, queueStore)
    val player = PlayerHolder(context)
}

class MusicConvertApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
