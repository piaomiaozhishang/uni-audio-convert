package com.musicconvert.ffmpeg

import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ffmpeg-kit 薄封装。只做两件事：拼命令、把 Statistics 回调换成 0..1 进度。
 *
 * 注意 ffmpeg-kit 的参数是自己解析的，路径一律加单引号，否则中文/空格路径必炸。
 */
object FfmpegRunner {

    suspend fun run(
        input: File,
        output: File,
        args: List<String>,
        durationMs: Long,
        extraInputs: List<File> = emptyList(),
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val command = buildString {
            append("-y -hide_banner -i ")
            append(quote(input.absolutePath))
            for (extra in extraInputs) {
                append(" -i ")
                append(quote(extra.absolutePath))
            }
            append(' ')
            append(args.joinToString(" "))
            append(' ')
            append(quote(output.absolutePath))
        }

        suspendCancellableCoroutine<Unit> { cont ->
            FFmpegKit.executeAsync(
                command,
                { session ->
                    val rc = session.returnCode
                    if (ReturnCode.isSuccess(rc)) {
                        if (cont.isActive) cont.resume(Unit)
                    } else {
                        val detail = session.failStackTrace
                            ?.lineSequence()
                            ?.firstOrNull { it.isNotBlank() }
                            ?: session.output?.take(200)
                            ?: ""
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException(
                                    "FFmpeg 转换失败" + if (detail.isBlank()) "" else "：${detail.take(160)}"
                                )
                            )
                        }
                    }
                },
                null,
                { statistics ->
                    if (durationMs > 0L) {
                        val p = (statistics.time.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        onProgress(p)
                    }
                }
            )
        }
    }

    fun probeDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** ffmpeg-kit 的参数解析器认单引号，值里有空格/中文时必须包起来。 */
    fun quoteValue(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun quote(path: String): String = quoteValue(path)
}
