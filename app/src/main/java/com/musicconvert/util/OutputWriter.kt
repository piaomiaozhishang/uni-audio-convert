package com.musicconvert.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 把处理好的文件放进目标目录。
 *
 * 优先级：用户在设置里选的 SAF 目录 → 系统音乐库 Music/MusicConvert（API 29+ 走 MediaStore）。
 * SAF 目录失效（被删/拔卡）时自动回退，不让整个转换失败。
 */
object OutputWriter {

    private const val FOLDER = "MusicConvert"
    private const val BUFFER = 64 * 1024

    fun save(
        context: Context,
        source: File,
        displayName: String,
        mime: String,
        treeUri: String? = null,
    ): String {
        if (!treeUri.isNullOrBlank()) {
            saveToTree(context, treeUri, source, displayName, mime)?.let { return it }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, source, displayName, mime)
        } else {
            saveToPublicDir(context, source, displayName)
        }
    }

    private fun saveToTree(
        context: Context,
        treeUri: String,
        source: File,
        displayName: String,
        mime: String,
    ): String? {
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            if (root == null || !root.canWrite()) return null
            val dest = root.createFile(mime, displayName) ?: return null
            context.contentResolver.openOutputStream(dest.uri)?.use { out ->
                source.inputStream().use { it.copyTo(out, BUFFER) }
            } ?: return null
            dest.uri.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        source: File,
        displayName: String,
        mime: String,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法在音乐库中创建文件")

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out, BUFFER) }
        } ?: throw IllegalStateException("无法写入音乐库")

        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicDir(context: Context, source: File, displayName: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            FOLDER
        )
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建输出目录")
        val dest = uniqueFile(dir, displayName)
        source.copyTo(dest, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        return "file://${dest.absolutePath}"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val suffix = if (ext.isBlank()) "" else ".$ext"
            candidate = File(dir, "$base ($index)$suffix")
            index++
        }
        return candidate
    }

    /** 去掉文件名里不能用的字符。 */
    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim()
        return cleaned.ifBlank { "audio" }.take(120)
    }
}
