package com.ddl.eori.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ddl.eori.data.remote.AiClient
import com.ddl.eori.data.remote.asParseError
import com.ddl.eori.data.settings.AiConfig
import com.ddl.eori.data.settings.SettingsRepository
import com.ddl.eori.data.sync.SyncConfig
import com.ddl.eori.data.sync.SyncDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 验证状态 */
sealed interface VerifyState {
    data object Idle : VerifyState
    data object Loading : VerifyState
    data class Success(val models: List<String>) : VerifyState
    data class Failure(val message: String) : VerifyState
}

data class SettingsUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val keyVisible: Boolean = false,
    val verifyState: VerifyState = VerifyState.Idle,
    val saved: Boolean = false,
    val message: String? = null,
    val darkMode: Boolean = false,
    // ---- 云同步 ----
    val syncEndpoint: String = "",
    val syncToken: String = "",
    val syncTokenVisible: Boolean = false,
    val syncRunning: Boolean = false,
    /** 最近一次成功同步的时间，null 表示从未同步 */
    val lastSyncAt: Long? = null,
    val lastSyncSummary: String? = null,
    val syncError: String? = null,
    /** 地址是否满足 https 要求 */
    val syncSecure: Boolean = true,
) {
    val models: List<String>
        get() = (verifyState as? VerifyState.Success)?.models.orEmpty()

    val canVerify: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
}

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val aiClient: AiClient,
    private val syncEngine: com.ddl.eori.data.sync.SyncEngine,
) : ViewModel() {

    private val _form = MutableStateFlow(FormState())
    private val _message = MutableStateFlow<String?>(null)
    private var loaded = false

    private data class FormState(
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        val keyVisible: Boolean = false,
        val verifyState: VerifyState = VerifyState.Idle,
        val syncEndpoint: String = "",
        val syncToken: String = "",
        val syncTokenVisible: Boolean = false,
    )

    private val _syncRunning = MutableStateFlow(false)
    private val _syncError = MutableStateFlow<String?>(null)

    init {
        // 首次加载已保存配置（只执行一次，避免覆盖用户正在编辑的内容）
        viewModelScope.launch {
            val config = settingsRepo.aiConfig.first()
            val sync = settingsRepo.syncPrefs.first()
            if (!loaded) {
                _form.value = FormState(
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    model = config.model,
                    syncEndpoint = sync.endpoint,
                    syncToken = sync.token,
                )
                loaded = true
            }
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        _form,
        _message,
        settingsRepo.uiPrefs,
        settingsRepo.syncPrefs,
        _syncRunning,
        _syncError,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val form = values[0] as FormState
        val prefs = values[2] as com.ddl.eori.data.settings.UiPrefs
        val sync = values[3] as com.ddl.eori.data.settings.SyncPrefs
        SettingsUiState(
            baseUrl = form.baseUrl,
            apiKey = form.apiKey,
            model = form.model,
            keyVisible = form.keyVisible,
            verifyState = form.verifyState,
            message = values[1] as String?,
            darkMode = prefs.darkMode,
            syncEndpoint = form.syncEndpoint,
            syncToken = form.syncToken,
            syncTokenVisible = form.syncTokenVisible,
            syncRunning = values[4] as Boolean,
            lastSyncAt = sync.lastSyncAt,
            lastSyncSummary = sync.lastSummary,
            syncError = values[5] as String?,
            syncSecure = SyncConfig.normalize(form.syncEndpoint).let {
                it.isBlank() || SyncConfig(endpoint = it).isSecureEndpoint
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    // ------------------------------------------------------------------ 表单编辑

    fun onBaseUrlChange(value: String) {
        _form.value = _form.value.copy(
            baseUrl = value.trim(),
            // 修改地址后旧模型列表失效
            verifyState = VerifyState.Idle,
        )
    }

    fun onApiKeyChange(value: String) {
        _form.value = _form.value.copy(apiKey = value.trim(), verifyState = VerifyState.Idle)
    }

    fun onModelChange(value: String) {
        _form.value = _form.value.copy(model = value)
    }

    fun toggleKeyVisible() {
        _form.value = _form.value.copy(keyVisible = !_form.value.keyVisible)
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ------------------------------------------------------------------ 验证 / 保存

    /**
     * 验证：GET {base}/models，成功则拉取模型列表供选择。
     * 失败区分网络/鉴权/格式，给出明确提示。
     */
    fun verify() {
        val form = _form.value
        if (form.baseUrl.isBlank() || form.apiKey.isBlank()) {
            _message.value = "请先填写 Base URL 和 API Key"
            return
        }
        if (form.verifyState is VerifyState.Loading) return

        viewModelScope.launch {
            _form.value = _form.value.copy(verifyState = VerifyState.Loading)
            val result = aiClient.listModels(form.baseUrl, form.apiKey)
            result.fold(
                onSuccess = { models ->
                    _form.value = _form.value.copy(
                        verifyState = VerifyState.Success(models),
                        // 自动选中一个可用模型
                        model = form.model.takeIf { it.isNotBlank() && it in models }
                            ?: models.first(),
                    )
                    _message.value = "验证成功，共 ${models.size} 个模型"
                },
                onFailure = { e ->
                    val error = e.asParseError()
                    val message = when (error) {
                        is com.ddl.eori.domain.ParseError.Http -> when (error.code) {
                            401, 403 -> "鉴权失败（${error.code}）：API Key 无效或无权限"
                            404 -> "接口不存在（404）：请检查 Base URL 是否需要 /v1"
                            429 -> "请求过于频繁（429），请稍后再试"
                            else -> "服务返回错误（${error.code}）：${error.detail}"
                        }
                        is com.ddl.eori.domain.ParseError.Network -> "网络异常：${error.detail}"
                        is com.ddl.eori.domain.ParseError.InvalidContent -> "响应格式不正确：不是 OpenAI 兼容接口"
                        else -> error.toMessage()
                    }
                    _form.value = _form.value.copy(verifyState = VerifyState.Failure(message))
                    _message.value = message
                },
            )
        }
    }

    fun save() {
        val form = _form.value
        if (form.baseUrl.isBlank()) {
            _message.value = "请填写 Base URL"
            return
        }
        if (form.apiKey.isBlank()) {
            _message.value = "请填写 API Key"
            return
        }
        if (form.model.isBlank()) {
            _message.value = "请先验证并选择模型"
            return
        }
        viewModelScope.launch {
            runCatching {
                settingsRepo.saveAiConfig(
                    AiConfig(
                        baseUrl = form.baseUrl.trimEnd('/'),
                        apiKey = form.apiKey,
                        model = form.model,
                    )
                )
            }.onSuccess {
                _message.value = "配置已保存"
            }.onFailure {
                _message.value = "保存失败：${it.message}"
            }
        }
    }

    // ------------------------------------------------------------------ 云同步

    fun onSyncEndpointChange(value: String) {
        _form.value = _form.value.copy(syncEndpoint = value.trim())
        _syncError.value = null
    }

    fun onSyncTokenChange(value: String) {
        _form.value = _form.value.copy(syncToken = value.trim())
        _syncError.value = null
    }

    fun toggleSyncTokenVisible() {
        _form.value = _form.value.copy(syncTokenVisible = !_form.value.syncTokenVisible)
    }

    /** 校验连接（只是一次 meta 请求，不改动任何数据） */
    fun testSyncConnection() {
        val config = currentSyncConfig() ?: return
        viewModelScope.launch {
            _syncRunning.value = true
            _syncError.value = null
            runCatching {
                val (meta, localLast) = syncEngine.check(config)
                settingsRepo.saveSyncConfig(config.endpoint, config.token)
                "连接成功：云端 ${meta.transactionCount} 笔账单 / ${meta.accountCount} 个账户" to
                    (if (meta.lastUpdatedAt >= localLast) "云端较新" else "本地较新")
            }.onSuccess { (text, hint) ->
                _message.value = "$text（$hint）"
            }.onFailure {
                _syncError.value = it.message ?: "连接失败"
            }
            _syncRunning.value = false
        }
    }

    /**
     * 手动同步：先比较两端最后更新时间戳决定方向，再只传增量。
     */
    fun syncNow() {
        if (_syncRunning.value) return
        val config = currentSyncConfig() ?: return
        viewModelScope.launch {
            _syncRunning.value = true
            _syncError.value = null
            runCatching {
                // 先保存配置，确保下次打开还在
                settingsRepo.saveSyncConfig(config.endpoint, config.token)
                syncEngine.run(config)
            }.onSuccess { outcome ->
                val direction = when (outcome.direction) {
                    SyncDirection.PULL -> "已从云端拉取"
                    SyncDirection.PUSH -> "已上传到云端"
                    SyncDirection.UP_TO_DATE -> "已是最新"
                }
                val detail = if (outcome.direction == SyncDirection.UP_TO_DATE) {
                    "两端数据一致，无需传输"
                } else {
                    "账户 ${outcome.accountsSynced} 个 · 账单 ${outcome.transactionsSynced} 笔"
                }
                val summary = "$direction · $detail"
                settingsRepo.recordSync(outcome.finishedAt, summary)
                _message.value = "同步完成：$summary"
            }.onFailure {
                _syncError.value = it.message ?: "同步失败"
            }
            _syncRunning.value = false
        }
    }

    private fun currentSyncConfig(): SyncConfig? {
        val form = _form.value
        val endpoint = SyncConfig.normalize(form.syncEndpoint)
        if (endpoint.isBlank() || form.syncToken.isBlank()) {
            _syncError.value = "请先填写同步地址和同步密钥"
            return null
        }
        val config = SyncConfig(endpoint, form.syncToken)
        if (!config.isSecureEndpoint) {
            _syncError.value = "同步地址必须使用 https://（明文会泄露账单与密钥）"
            return null
        }
        return config
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { settingsRepo.setDarkMode(enabled) }
        }
    }
}
