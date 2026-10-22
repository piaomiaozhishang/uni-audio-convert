package com.musicconvert.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NowPlaying(
    val uri: String,
    val title: String,
)

/**
 * 全局唯一的播放器实例。转换前后试听用，够用就好，不搞 MediaSession 那套。
 */
class PlayerHolder(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
    }

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    fun play(uri: String, title: String) {
        if (_nowPlaying.value?.uri == uri) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
        _nowPlaying.value = NowPlaying(uri, title)
    }

    fun stop() {
        player.stop()
        _nowPlaying.value = null
    }

    fun release() {
        player.release()
        _nowPlaying.value = null
    }
}
