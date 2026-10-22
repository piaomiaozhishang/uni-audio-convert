package com.musicconvert.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryEntry(
    val id: Long,
    val fileName: String,
    val sourceName: String,
    val formatLabel: String,
    val sizeBytes: Long,
    val success: Boolean,
    val message: String?,
    val timestamp: Long,
    val outputUri: String?,
)

/**
 * 历史记录。表结构极简，没必要为它引入 Room + KSP（多一个注解处理器就多一份构建风险）。
 */
class HistoryStore(context: Context) {

    private val helper = object : SQLiteOpenHelper(context, "history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE history(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    fileName TEXT NOT NULL,
                    sourceName TEXT NOT NULL,
                    formatLabel TEXT NOT NULL,
                    sizeBytes INTEGER NOT NULL,
                    success INTEGER NOT NULL,
                    message TEXT,
                    timestamp INTEGER NOT NULL,
                    outputUri TEXT
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    fun insert(entry: HistoryEntry): Long {
        val values = ContentValues().apply {
            put("fileName", entry.fileName)
            put("sourceName", entry.sourceName)
            put("formatLabel", entry.formatLabel)
            put("sizeBytes", entry.sizeBytes)
            put("success", if (entry.success) 1 else 0)
            put("message", entry.message)
            put("timestamp", entry.timestamp)
            put("outputUri", entry.outputUri)
        }
        return helper.writableDatabase.insert("history", null, values)
    }

    fun queryAll(limit: Int = 500): List<HistoryEntry> {
        val list = ArrayList<HistoryEntry>()
        helper.readableDatabase.query(
            "history", null, null, null, null, null, "timestamp DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    HistoryEntry(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        fileName = c.getString(c.getColumnIndexOrThrow("fileName")),
                        sourceName = c.getString(c.getColumnIndexOrThrow("sourceName")),
                        formatLabel = c.getString(c.getColumnIndexOrThrow("formatLabel")),
                        sizeBytes = c.getLong(c.getColumnIndexOrThrow("sizeBytes")),
                        success = c.getInt(c.getColumnIndexOrThrow("success")) == 1,
                        message = c.getString(c.getColumnIndexOrThrow("message")),
                        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
                        outputUri = c.getString(c.getColumnIndexOrThrow("outputUri")),
                    )
                )
            }
        }
        return list
    }

    fun delete(id: Long) {
        helper.writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
    }

    fun clear() {
        helper.writableDatabase.delete("history", null, null)
    }
}
