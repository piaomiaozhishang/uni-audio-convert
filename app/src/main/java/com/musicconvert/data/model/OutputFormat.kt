package com.musicconvert.data.model

/** 输出格式，codecArgs 直接拼进 ffmpeg 命令行。 */
enum class OutputFormat(
    val label: String,
    val ext: String,
    val mime: String,
    val lossless: Boolean,
    val supportsBitrate: Boolean,
    val supportsCover: Boolean,
    /** 容器与编码一一对应，解密结果同格式时可以直接搬，不用重编码 */
    val directCopySafe: Boolean,
    val defaultBitrate: Int,
) {
    MP3("MP3", "mp3", "audio/mpeg", false, true, true, true, 320),
    M4A("M4A（AAC）", "m4a", "audio/mp4", false, true, true, false, 256),
    FLAC("FLAC", "flac", "audio/flac", true, false, true, true, 0),
    WAV("WAV", "wav", "audio/wav", true, false, false, true, 0),
    ALAC("ALAC", "m4a", "audio/mp4", true, false, true, false, 0),
    OGG("OGG（Vorbis）", "ogg", "audio/ogg", false, true, true, false, 192),
    OPUS("Opus", "opus", "audio/opus", false, true, false, false, 160),
    AIFF("AIFF", "aiff", "audio/aiff", true, false, false, true, 0),
    WMA("WMA", "wma", "audio/x-ms-wma", false, true, false, false, 192);

    fun codecArgs(bitrate: Int): List<String> = when (this) {
        MP3 -> listOf("-c:a", "libmp3lame", "-b:a", "${bitrate}k")
        M4A -> listOf("-c:a", "aac", "-b:a", "${bitrate}k", "-f", "ipod")
        FLAC -> listOf("-c:a", "flac", "-compression_level", "8")
        WAV -> listOf("-c:a", "pcm_s16le")
        ALAC -> listOf("-c:a", "alac", "-f", "ipod")
        OGG -> listOf("-c:a", "libvorbis", "-b:a", "${bitrate}k")
        OPUS -> listOf("-c:a", "libopus", "-b:a", "${bitrate}k")
        AIFF -> listOf("-c:a", "pcm_s16be")
        WMA -> listOf("-c:a", "wmav2", "-b:a", "${bitrate}k")
    }

    companion object {
        val BITRATES = listOf(64, 96, 128, 160, 192, 256, 320)
    }
}
