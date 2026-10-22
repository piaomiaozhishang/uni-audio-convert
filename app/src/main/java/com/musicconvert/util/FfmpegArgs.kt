package com.musicconvert.util

import com.musicconvert.data.AppSettings
import com.musicconvert.data.model.ConvertTask
import com.musicconvert.decrypt.DecryptedAudio
import com.musicconvert.ffmpeg.FfmpegRunner

/**
 * ffmpeg 转码参数构造。抽成纯函数是为了能直接单测——
 * 尤其是封面分支：加 `-map 1:v` 之后绝不能再带 `-vn`，否则封面会被丢掉。
 */
object FfmpegArgs {

    fun build(
        task: ConvertTask,
        meta: DecryptedAudio?,
        settings: AppSettings,
        hasCover: Boolean,
    ): List<String> = buildList {
        if (hasCover) {
            add("-map"); add("0:a")
            add("-map"); add("1:v")
            add("-c:v"); add("copy")
            add("-disposition:v"); add("attached_pic")
        } else {
            add("-vn")
        }
        if (settings.keepOriginalTags) {
            add("-map_metadata")
            add("0")
        }
        addAll(task.outputFormat.codecArgs(task.bitrate))
        if (meta != null && settings.keepMetadata) {
            meta.title?.takeIf { it.isNotBlank() }?.let {
                add("-metadata"); add("title=" + FfmpegRunner.quoteValue(it))
            }
            meta.artist?.takeIf { it.isNotBlank() }?.let {
                add("-metadata"); add("artist=" + FfmpegRunner.quoteValue(it))
            }
            meta.album?.takeIf { it.isNotBlank() }?.let {
                add("-metadata"); add("album=" + FfmpegRunner.quoteValue(it))
            }
        }
    }
}
