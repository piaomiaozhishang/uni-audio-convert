package com.musicconvert.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.musicconvert.data.AppSettings
import com.musicconvert.data.HistoryEntry
import com.musicconvert.data.HistoryStore
import com.musicconvert.data.QueueStore
import com.musicconvert.data.SettingsStore
import com.musicconvert.data.model.ConvertTask
import com.musicconvert.data.model.OutputFormat
import com.musicconvert.data.model.TaskStatus
import com.musicconvert.decrypt.DecryptedAudio
import com.musicconvert.decrypt.EkeyStore
import com.musicconvert.decrypt.EncryptedKind
import com.musicconvert.decrypt.MusicDecryptor
import com.musicconvert.ffmpeg.FfmpegRunner
import com.musicconvert.util.FfmpegArgs
import com.musicconvert.util.OutputWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 转换队列。刻意做成串行：手机上同时跑多个 ffmpeg 进程会直接把内存打爆。
 * 解密 → 转码 → 落盘 → 写历史，一条龙；任何一步失败都只影响当前这一条。
 */
class ConvertEngine(
    private val context: Context,
    private val historyStore: HistoryStore,
    private val settingsStore: SettingsStore,
    private val ekeyStore: EkeyStore,
    private val queueStore: QueueStore,
) {

    private companion object {
        /** 复制大文件用 64KB 缓冲，默认的 8KB 在低端机上是明显的瓶颈 */
        const val COPY_BUFFER = 64 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 上次没跑完的任务（进程被杀时留下的），恢复成待处理 */
    private val restored = queueStore.load()
    private val nextId = AtomicLong((restored.maxOfOrNull { it.id } ?: 0L) + 1)

    private val _queue = MutableStateFlow(restored)
    val queue: StateFlow<List<ConvertTask>> = _queue.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var loopJob: Job? = null

    fun enqueue(uris: List<Uri>, format: OutputFormat, bitrate: Int) {
        if (uris.isEmpty()) return
        scope.launch {
            val added = uris.mapNotNull { uri ->
                // 不拿持久权限的话，进程重启后这个 URI 就读不到了
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val meta = queryMeta(uri) ?: return@mapNotNull null
                ConvertTask(
                    id = nextId.getAndIncrement(),
                    sourceUri = uri.toString(),
                    sourceName = meta.first,
                    sizeBytes = meta.second,
                    kind = MusicDecryptor.sniff(meta.first, null),
                    outputFormat = format,
                    bitrate = bitrate,
                )
            }
            if (added.isNotEmpty()) {
                _queue.update { it + added }
                queueStore.save(_queue.value)
            }
        }
    }

    fun remove(id: Long) {
        _queue.update { list -> list.filterNot { it.id == id && !it.isRunning } }
        queueStore.save(_queue.value)
    }

    fun clearFinished() {
        _queue.update { list -> list.filter { it.status == TaskStatus.PENDING || it.isRunning } }
        queueStore.save(_queue.value)
    }

    fun start() {
        if (_running.value) return
        loopJob = scope.launch {
            _running.value = true
            try {
                while (true) {
                    val next = _queue.value.firstOrNull { it.status == TaskStatus.PENDING } ?: break
                    process(next)
                }
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _queue.update { list ->
            list.map {
                when {
                    it.isRunning -> it.copy(status = TaskStatus.FAILED, message = "已取消")
                    it.status == TaskStatus.PENDING -> it.copy(status = TaskStatus.FAILED, message = "已取消")
                    else -> it
                }
            }
        }
        _running.value = false
        queueStore.save(_queue.value)
    }

    private suspend fun process(task: ConvertTask) {
        val settings = settingsStore.settings.first()
        val baseDir = context.externalCacheDir ?: context.cacheDir
        val work = File(baseDir, "work/${task.id}").apply { mkdirs() }
        var producedUri: String? = null

        try {
            val rawExt = task.sourceName.substringAfterLast('.', "bin")
            val rawIn = File(work, "in.$rawExt")
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(Uri.parse(task.sourceUri))?.use { input ->
                    rawIn.outputStream().use { input.copyTo(it, COPY_BUFFER) }
                } ?: throw IllegalStateException("无法读取源文件")
            }

            val kind = MusicDecryptor.sniff(task.sourceName, rawIn)
            var meta: DecryptedAudio? = null

            // 进度按 1% 粒度上报：ffmpeg/解密每秒回调十几次，每次都会触发列表重组，
            // 低端机上这就是明显的掉帧来源。
            var lastPct = -1
            fun report(p: Float, from: Float, span: Float) {
                val global = from + p * span
                val pct = (global * 100).toInt()
                if (pct == lastPct) return
                lastPct = pct
                updateTask(task.id) { it.copy(progress = global) }
            }

            val audioSource: File = if (kind != EncryptedKind.UNKNOWN) {
                updateTask(task.id) { it.copy(status = TaskStatus.DECRYPTING, progress = 0f) }
                val decoded = File(work, "decoded.bin")
                val result = MusicDecryptor.decrypt(
                    input = rawIn,
                    output = decoded,
                    kind = kind,
                    fileName = task.sourceName,
                    ekeyLookup = { names -> ekeyStore.lookup(names) },
                    onProgress = { p -> report(p, 0f, 0.45f) },
                )
                meta = result
                result.file
            } else {
                rawIn
            }

            // 同容器同编码：直接搬文件，跳过重编码。
            // 既省掉绝大部分耗时，也避免把 320k 的 mp3 再压一遍造成二次损失。
            val sourceExt = meta?.containerExt?.lowercase()
            if (settings.preferDirectCopy &&
                sourceExt != null &&
                task.outputFormat.directCopySafe &&
                sourceExt == task.outputFormat.ext
            ) {
                // 直通不需要时长（没有进度要算），省掉一次 ffmpeg 会话
                updateTask(task.id) {
                    it.copy(status = TaskStatus.CONVERTING, progress = 0.9f)
                }
                val rawBase = meta.title?.takeIf { it.isNotBlank() }
                    ?: task.sourceName.substringBeforeLast('.')
                val name = OutputWriter.sanitize(rawBase) + "." + task.outputFormat.ext
                producedUri = OutputWriter.save(
                    context = context,
                    source = audioSource,
                    displayName = name,
                    mime = task.outputFormat.mime,
                    treeUri = settings.outputTreeUri,
                )
                updateTask(task.id) {
                    it.copy(
                        status = TaskStatus.DONE,
                        progress = 1f,
                        outputName = name,
                        outputPath = producedUri,
                        message = "直接导出（未重编码）",
                    )
                }
                record(
                    task.copy(outputName = name, outputPath = producedUri),
                    true,
                    "直接导出（未重编码）",
                    producedUri,
                )
                return
            }

            val duration = FfmpegRunner.probeDurationMs(audioSource)
            updateTask(task.id) {
                it.copy(status = TaskStatus.CONVERTING, progress = 0.45f, durationMs = duration)
            }

            val outFile = File(work, "out.${task.outputFormat.ext}")

            // 加密文件里带的封面：塞进输出文件，省得用户再去补图
            val coverFile = meta?.cover
                ?.takeIf { it.isNotEmpty() && task.outputFormat.supportsCover }
                ?.let { bytes -> File(work, "cover.img").apply { writeBytes(bytes) } }

            FfmpegRunner.run(
                input = audioSource,
                output = outFile,
                args = FfmpegArgs.build(task, meta, settings, coverFile != null),
                durationMs = duration,
                extraInputs = listOfNotNull(coverFile),
            ) { p ->
                report(p, 0.45f, 0.55f)
            }

            val rawBase = meta?.title?.takeIf { it.isNotBlank() }
                ?: task.sourceName.substringBeforeLast('.')
            val displayName = OutputWriter.sanitize(rawBase) + "." + task.outputFormat.ext
            producedUri = OutputWriter.save(
                context = context,
                source = outFile,
                displayName = displayName,
                mime = task.outputFormat.mime,
                treeUri = settings.outputTreeUri,
            )

            updateTask(task.id) {
                it.copy(
                    status = TaskStatus.DONE,
                    progress = 1f,
                    outputName = displayName,
                    outputPath = producedUri,
                )
            }
            record(
                task.copy(outputName = displayName, outputPath = producedUri),
                true,
                null,
                producedUri,
            )
        } catch (e: CancellationException) {
            updateTask(task.id) { it.copy(status = TaskStatus.FAILED, message = "已取消") }
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "未知错误"
            updateTask(task.id) { it.copy(status = TaskStatus.FAILED, message = message) }
            record(task, false, message, null)
        } finally {
            runCatching { work.deleteRecursively() }
            queueStore.save(_queue.value)
        }
    }

    private suspend fun record(
        task: ConvertTask,
        success: Boolean,
        message: String?,
        uri: String?,
    ) {
        withContext(Dispatchers.IO) {
            historyStore.insert(
                HistoryEntry(
                    id = 0,
                    fileName = task.outputName ?: "",
                    sourceName = task.sourceName,
                    formatLabel = task.outputFormat.label,
                    sizeBytes = task.sizeBytes,
                    success = success,
                    message = message,
                    timestamp = System.currentTimeMillis(),
                    outputUri = uri,
                )
            )
        }
    }

    private fun updateTask(id: Long, block: (ConvertTask) -> ConvertTask) {
        _queue.update { list -> list.map { if (it.id == id) block(it) else it } }
    }

    private fun queryMeta(uri: Uri): Pair<String, Long>? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0) ?: uri.lastPathSegment ?: return null
                    val size = if (!c.isNull(1)) c.getLong(1) else 0L
                    return name to size
                }
            }
        } catch (_: Exception) {
            // 某些 provider 不支持 query，退回用路径末段当文件名
        }
        return uri.lastPathSegment?.let { it to 0L }
    }
}
