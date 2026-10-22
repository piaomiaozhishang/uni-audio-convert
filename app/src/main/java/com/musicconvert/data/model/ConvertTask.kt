package com.musicconvert.data.model

import com.musicconvert.decrypt.EncryptedKind

enum class TaskStatus { PENDING, DECRYPTING, CONVERTING, DONE, FAILED }

/**
 * 队列里的一条转换任务。progress 只在 DECRYPTING/CONVERTING 时有意义。
 */
data class ConvertTask(
    val id: Long,
    val sourceUri: String,
    val sourceName: String,
    val sizeBytes: Long,
    val kind: EncryptedKind,
    val outputFormat: OutputFormat,
    val bitrate: Int,
    val status: TaskStatus = TaskStatus.PENDING,
    val progress: Float = 0f,
    val message: String? = null,
    val outputName: String? = null,
    val outputPath: String? = null,
    val durationMs: Long = 0L,
) {
    val isEncrypted: Boolean get() = kind != EncryptedKind.UNKNOWN
    val isRunning: Boolean get() = status == TaskStatus.DECRYPTING || status == TaskStatus.CONVERTING
}
