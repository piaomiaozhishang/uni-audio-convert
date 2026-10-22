package com.musicconvert.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.musicconvert.MainActivity
import com.musicconvert.MusicConvertApp
import com.musicconvert.data.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 只是让进程在前台活下去（切后台不被杀）+ 显示进度。
 * 真正的活儿在 ConvertEngine 里。
 */
class ConvertService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var engine: ConvertEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = (application as MusicConvertApp).container.engine
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            engine.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground("准备转换", null)

        scope.launch {
            engine.queue.collectLatest { queue ->
                if (queue.isEmpty()) return@collectLatest
                val active = queue.firstOrNull { it.isRunning }
                val finished = queue.count { it.status == TaskStatus.DONE || it.status == TaskStatus.FAILED }
                val remaining = queue.count { it.status == TaskStatus.PENDING }

                if (active != null) {
                    val text = active.sourceName.take(32) +
                        "  " + (active.progress * 100).toInt() + "%"
                    startInForeground(text, active.progress)
                } else if (remaining == 0 && !engine.running.value) {
                    val ok = queue.count { it.status == TaskStatus.DONE }
                    notifyFinished(ok, finished - ok)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(text: String, progress: Float?) {
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ConvertService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("正在转换音频")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_delete, "取消", cancelIntent)

        if (progress != null) {
            builder.setProgress(100, (progress * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyFinished(success: Int, failed: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = buildString {
            append("成功 ").append(success).append(" 个")
            if (failed > 0) append("，失败 ").append(failed).append(" 个")
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("转换完成")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "转换任务", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "显示批量转换的进度" }
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "convert_progress"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL = "com.musicconvert.CANCEL"

        fun start(context: Context) {
            val intent = Intent(context, ConvertService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
