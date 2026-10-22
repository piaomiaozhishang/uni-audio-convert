package com.musicconvert.decrypt

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * 导入的 QQ 音乐密钥仓库（STag / musicex 文件用）。
 *
 * 只收两种格式：
 *  1. JSON：{"文件名": "base64 EKey", ...}（unlock-music 的 mmkv 导出、手工整理都行）
 *  2. 文本：每行 `文件名<TAB或=或,>base64 EKey`
 *
 * 匹配顺序：完整文件名 → 去扩展名的词干（"song.mflac0.flac" 与 "song.mflac" 视为同一首）。
 */
class EkeyStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ekeys", Context.MODE_PRIVATE)

    @Volatile
    private var cache: Map<String, String>? = null

    val size: Int get() = all().size

    private fun all(): Map<String, String> {
        cache?.let { return it }
        val map = HashMap<String, String>()
        for ((k, v) in prefs.all) {
            if (v is String && v.isNotBlank()) map[k] = v
        }
        cache = map
        return map
    }

    fun lookup(candidates: List<String>): String? {
        val map = all()
        if (map.isEmpty()) return null
        for (name in candidates) {
            map[name]?.let { return it }
        }
        // 词干匹配
        val stems = candidates.map { stem(it) }.filter { it.isNotBlank() }
        for (s in stems) {
            map[s]?.let { return it }
        }
        for ((key, value) in map) {
            if (stem(key) in stems) return value
        }
        return null
    }

    /**
     * 读取用户选中的文件，返回成功导入的条数。
     * 依次尝试：JSON → 行文本 → MMKV 二进制（QQ 音乐的 MMKVStreamEncryptId）。
     */
    fun importFrom(uri: Uri, context: Context): Int {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取该文件")

        val parsed = parseText(bytes).takeIf { it.isNotEmpty() }
            ?: runCatching { MmkvParser.parse(bytes) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: emptyMap()

        if (parsed.isEmpty()) {
            throw IllegalArgumentException("没有解析到有效密钥（支持 JSON / 文本 / MMKV）")
        }

        val editor = prefs.edit()
        for ((name, key) in parsed) editor.putString(name, key)
        editor.apply()
        cache = null
        return parsed.size
    }

    private fun parseText(bytes: ByteArray): Map<String, String> {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return emptyMap()
        if (text.startsWith("{")) {
            val fromJson = parseJson(text)
            if (fromJson.isNotEmpty()) return fromJson
        }
        return parseLines(text)
    }

    private fun parseJson(text: String): Map<String, String> {
        return try {
            val obj = JSONObject(text)
            val out = HashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k)
                if (v.isNotBlank()) out[k] = v.trim()
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseLines(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val sepIndex = l.indexOfFirst { it == '\t' || it == '=' || it == ',' }
            if (sepIndex <= 0) continue
            val name = l.substring(0, sepIndex).trim()
            val key = l.substring(sepIndex + 1).trim()
            if (name.isNotEmpty() && key.isNotEmpty()) out[name] = key
        }
        return out
    }

    fun clear() {
        prefs.edit().clear().apply()
        cache = null
    }

    private fun stem(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val parts = base.split('.')
        return if (parts.size <= 1) base else parts.first()
    }
}
