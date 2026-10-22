package com.musicconvert.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.musicconvert.data.model.OutputFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val defaultFormat: OutputFormat = OutputFormat.MP3,
    val defaultBitrate: Int = 320,
    val keepMetadata: Boolean = true,
    val keepOriginalTags: Boolean = true,
    val notifyOnFinish: Boolean = true,
    val dynamicColor: Boolean = false,
    val outputTreeUri: String? = null,
    val preferDirectCopy: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val FORMAT = stringPreferencesKey("default_format")
        val BITRATE = intPreferencesKey("default_bitrate")
        val KEEP_METADATA = booleanPreferencesKey("keep_metadata")
        val KEEP_TAGS = booleanPreferencesKey("keep_tags")
        val NOTIFY = booleanPreferencesKey("notify_finish")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val OUTPUT_TREE = stringPreferencesKey("output_tree_uri")
        val DIRECT_COPY = booleanPreferencesKey("prefer_direct_copy")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            defaultFormat = p[Keys.FORMAT]
                ?.let { name -> runCatching { OutputFormat.valueOf(name) }.getOrNull() }
                ?: OutputFormat.MP3,
            defaultBitrate = p[Keys.BITRATE] ?: 320,
            keepMetadata = p[Keys.KEEP_METADATA] ?: true,
            keepOriginalTags = p[Keys.KEEP_TAGS] ?: true,
            notifyOnFinish = p[Keys.NOTIFY] ?: true,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: false,
            outputTreeUri = p[Keys.OUTPUT_TREE],
            preferDirectCopy = p[Keys.DIRECT_COPY] ?: true,
        )
    }

    suspend fun setFormat(format: OutputFormat) {
        context.dataStore.edit { it[Keys.FORMAT] = format.name }
    }

    suspend fun setBitrate(bitrate: Int) {
        context.dataStore.edit { it[Keys.BITRATE] = bitrate }
    }

    suspend fun setKeepMetadata(value: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_METADATA] = value }
    }

    suspend fun setKeepOriginalTags(value: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_TAGS] = value }
    }

    suspend fun setNotifyOnFinish(value: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY] = value }
    }

    suspend fun setDynamicColor(value: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = value }
    }

    suspend fun setOutputTreeUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.OUTPUT_TREE) else prefs[Keys.OUTPUT_TREE] = uri
        }
    }

    suspend fun setPreferDirectCopy(value: Boolean) {
        context.dataStore.edit { it[Keys.DIRECT_COPY] = value }
    }
}
