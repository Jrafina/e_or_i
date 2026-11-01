package com.ddl.eori.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** AI 配置（仅保存在本地） */
data class AiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    /** 是否具备发起解析请求的最低配置 */
    val isReady: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** 同步配置与状态（本地持久化） */
data class SyncPrefs(
    val endpoint: String = "",
    val token: String = "",
    /** 最近一次成功同步的时间戳，null 表示从未同步 */
    val lastSyncAt: Long? = null,
    /** 最近一次同步的摘要（方向 + 条数） */
    val lastSummary: String? = null,
) {
    val isReady: Boolean get() = endpoint.isNotBlank() && token.isNotBlank()
}

/** 界面偏好 */
data class UiPrefs(
    val darkMode: Boolean = false,
    val hasSeenSplashHint: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "eori_settings")

/**
 * 设置仓库：DataStore(Preferences)，全部为 suspend/Flow，天然在 IO 线程执行。
 * 读取时对 IOException 做兜底，避免损坏文件导致崩溃。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("ai_base_url")
        val API_KEY = stringPreferencesKey("ai_api_key")
        val MODEL = stringPreferencesKey("ai_model")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val SEEN_HINT = booleanPreferencesKey("seen_splash_hint")

        // 云同步
        val SYNC_ENDPOINT = stringPreferencesKey("sync_endpoint")
        val SYNC_TOKEN = stringPreferencesKey("sync_token")
        val SYNC_LAST_AT = stringPreferencesKey("sync_last_at")
        val SYNC_LAST_SUMMARY = stringPreferencesKey("sync_last_summary")
    }

    private val prefs: Flow<Preferences> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val aiConfig: Flow<AiConfig> = prefs.map { p ->
        AiConfig(
            baseUrl = p[Keys.BASE_URL].orEmpty(),
            apiKey = p[Keys.API_KEY].orEmpty(),
            model = p[Keys.MODEL].orEmpty(),
        )
    }

    val syncPrefs: Flow<SyncPrefs> = prefs.map { p ->
        SyncPrefs(
            endpoint = p[Keys.SYNC_ENDPOINT].orEmpty(),
            token = p[Keys.SYNC_TOKEN].orEmpty(),
            lastSyncAt = p[Keys.SYNC_LAST_AT]?.toLongOrNull(),
            lastSummary = p[Keys.SYNC_LAST_SUMMARY],
        )
    }

    val uiPrefs: Flow<UiPrefs> = prefs.map { p ->
        UiPrefs(
            darkMode = p[Keys.DARK_MODE] ?: false,
            hasSeenSplashHint = p[Keys.SEEN_HINT] ?: false,
        )
    }

    suspend fun saveAiConfig(config: AiConfig) {
        context.dataStore.edit { p ->
            p[Keys.BASE_URL] = config.baseUrl
            p[Keys.API_KEY] = config.apiKey
            p[Keys.MODEL] = config.model
        }
    }

    suspend fun saveSyncConfig(endpoint: String, token: String) {
        context.dataStore.edit { p ->
            p[Keys.SYNC_ENDPOINT] = endpoint.trim()
            p[Keys.SYNC_TOKEN] = token.trim()
        }
    }

    /** 记录一次成功同步的时间与摘要（设置页展示“最近同步时间”） */
    suspend fun recordSync(at: Long, summary: String) {
        context.dataStore.edit { p ->
            p[Keys.SYNC_LAST_AT] = at.toString()
            p[Keys.SYNC_LAST_SUMMARY] = summary
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_MODE] = enabled }
    }

    suspend fun setSeenHint(value: Boolean) {
        context.dataStore.edit { it[Keys.SEEN_HINT] = value }
    }
}
