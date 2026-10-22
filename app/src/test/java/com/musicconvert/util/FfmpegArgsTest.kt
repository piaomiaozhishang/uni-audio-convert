package com.musicconvert.util

import com.musicconvert.data.AppSettings
import com.musicconvert.data.model.ConvertTask
import com.musicconvert.data.model.OutputFormat
import com.musicconvert.decrypt.DecryptedAudio
import com.musicconvert.decrypt.EncryptedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 封面分支最容易出错的地方：加了 `-map 1:v` 还留着 `-vn`，封面会被静默丢掉；
 * 或者漏了 `-disposition:v attached_pic`，播放器不会把图当封面。
 */
class FfmpegArgsTest {

    private fun task(format: OutputFormat = OutputFormat.MP3, bitrate: Int = 320) = ConvertTask(
        id = 1,
        sourceUri = "content://x/1",
        sourceName = "song.qmc",
        sizeBytes = 1,
        kind = EncryptedKind.QMC,
        outputFormat = format,
        bitrate = bitrate,
    )

    private fun meta(title: String? = "歌名", artist: String? = "歌手", album: String? = "专辑") =
        DecryptedAudio(
            file = File("decoded.bin"),
            containerExt = "mp3",
            title = title,
            artist = artist,
            album = album,
        )

    @Test
    fun `no cover uses -vn and no map`() {
        val args = FfmpegArgs.build(task(), meta(), AppSettings(), hasCover = false)
        assertTrue(args.contains("-vn"))
        assertFalse(args.contains("-map"))
    }

    @Test
    fun `cover keeps video stream and never uses -vn`() {
        val args = FfmpegArgs.build(task(), meta(), AppSettings(), hasCover = true)
        assertFalse("-vn" in args)
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("-map 0:a"))
        assertTrue(joined.contains("-map 1:v"))
        assertTrue(joined.contains("-c:v copy"))
        assertTrue(joined.contains("-disposition:v attached_pic"))
    }

    @Test
    fun `metadata is written when enabled`() {
        val args = FfmpegArgs.build(task(), meta(), AppSettings(keepMetadata = true), hasCover = false)
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("title="))
        assertTrue(joined.contains("artist="))
        assertTrue(joined.contains("album="))
    }

    @Test
    fun `metadata skipped when disabled`() {
        val args = FfmpegArgs.build(task(), meta(), AppSettings(keepMetadata = false), hasCover = false)
        val joined = args.joinToString(" ")
        assertFalse(joined.contains("title="))
    }

    @Test
    fun `bitrate follows format and task`() {
        val args = FfmpegArgs.build(task(OutputFormat.MP3, 192), meta(), AppSettings(), hasCover = false)
        assertTrue(args.joinToString(" ").contains("-c:a libmp3lame -b:a 192k"))

        // FLAC 无码率概念：编码器参数里不应出现 k
        val flac = FfmpegArgs.build(
            task(OutputFormat.FLAC),
            meta(title = null, artist = null, album = null),
            AppSettings(),
            hasCover = false,
        )
        assertFalse(flac.any { it.endsWith("k") })
    }

    @Test
    fun `blank tags are dropped`() {
        val args = FfmpegArgs.build(
            task(),
            meta(title = "  ", artist = null, album = "专辑"),
            AppSettings(),
            hasCover = false,
        )
        val joined = args.joinToString(" ")
        assertFalse(joined.contains("title="))
        assertFalse(joined.contains("artist="))
        assertTrue(joined.contains("album="))
    }
}
