package com.musicconvert.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本检查。**只有点「检查更新」时才会联网**，其余功能全程离线。
 *
 * 走 Gitee 的 releases API（不需要 token，公开仓库可读）。
 * 仓库还没发版时会返回 404，这种情况明确告诉用户"暂无发布版本"而不是报网络错误。
 */
object UpdateChecker {

    const val RELEASES_PAGE = "https://gitee.com/piaomiaozhishang/uni-audio-convert/releases"
    private const val API =
        "https://gitee.com/api/v5/repos/piaomiaozhishang/uni-audio-convert/releases/latest"

    sealed interface Result {
        data class Newer(val version: String) : Result
        data object Latest : Result
        data class Failed(val message: String) : Result
    }

    suspend fun check(current: String): Result = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code == 404) return@withContext Result.Failed("仓库暂无发布版本")
            if (code != 200) return@withContext Result.Failed("服务器返回 $code")

            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val obj = JSONObject(text)
            val tag = obj.optString("tag_name").ifBlank { obj.optString("name") }
            if (tag.isBlank()) return@withContext Result.Failed("没有读到版本号")

            if (compare(tag, current) > 0) Result.Newer(tag) else Result.Latest
        } catch (e: Exception) {
            Result.Failed(e.message?.takeIf { it.isNotBlank() } ?: "网络不可用")
        } finally {
            conn?.disconnect()
        }
    }

    /** 数字段逐个比，支持 "v1.2.3" 与 "1.2.3-beta" 这类写法。 */
    fun compare(a: String, b: String): Int {
        val pa = segments(a)
        val pb = segments(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun segments(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
}
