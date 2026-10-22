package com.musicconvert.data

import android.content.Context
import com.musicconvert.data.model.ConvertTask
import com.musicconvert.data.model.OutputFormat
import com.musicconvert.data.model.TaskStatus
import com.musicconvert.decrypt.EncryptedKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 未完成任务的落盘，防止进程被系统杀掉后队列凭空消失。
 *
 * 只存 PENDING / 正在跑的任务：已完成的由历史记录负责，不需要恢复。
 * 恢复时统一重置为 PENDING——进程都重启了，之前那条肯定没在跑。
 */
class QueueStore(context: Context) {

    private val file = File(context.filesDir, "pending_queue.json")

    fun load(): List<ConvertTask> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ConvertTask(
                            id = o.optLong("id"),
                            sourceUri = o.getString("uri"),
                            sourceName = o.getString("name"),
                            sizeBytes = o.optLong("size"),
                            kind = runCatching {
                                EncryptedKind.valueOf(o.getString("kind"))
                            }.getOrDefault(EncryptedKind.UNKNOWN),
                            outputFormat = runCatching {
                                OutputFormat.valueOf(o.getString("fmt"))
                            }.getOrDefault(OutputFormat.MP3),
                            bitrate = o.optInt("br", 320),
                            status = TaskStatus.PENDING,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(tasks: List<ConvertTask>) {
        val pending = tasks.filter {
            it.status == TaskStatus.PENDING ||
                it.status == TaskStatus.DECRYPTING ||
                it.status == TaskStatus.CONVERTING
        }
        try {
            val arr = JSONArray()
            for (t in pending) {
                arr.put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("uri", t.sourceUri)
                        put("name", t.sourceName)
                        put("size", t.sizeBytes)
                        put("kind", t.kind.name)
                        put("fmt", t.outputFormat.name)
                        put("br", t.bitrate)
                    }
                )
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
            // 持久化失败不该让转换流程崩掉
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
