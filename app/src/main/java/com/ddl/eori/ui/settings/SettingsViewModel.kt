package com.ddl.eori.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ddl.eori.data.remote.AiClient
import com.ddl.eori.data.remote.asParseError
import com.ddl.eori.data.settings.AiConfig
import com.ddl.eori.data.settings.SettingsRepository
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
) {
    val models: List<String>
        get() = (verifyState as? VerifyState.Success)?.models.orEmpty()

    val canVerify: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
}

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val aiClient: AiClient,
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
    )

    init {
        // 首次加载已保存配置（只执行一次，避免覆盖用户正在编辑的内容）
        viewModelScope.launch {
            val config = settingsRepo.aiConfig.first()
            if (!loaded) {
                _form.value = FormState(
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    model = config.model,
                )
                loaded = true
            }
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        _form,
        _message,
        settingsRepo.uiPrefs,
    ) { form, message, prefs ->
        SettingsUiState(
            baseUrl = form.baseUrl,
            apiKey = form.apiKey,
            model = form.model,
            keyVisible = form.keyVisible,
            verifyState = form.verifyState,
            message = message,
            darkMode = prefs.darkMode,
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

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { settingsRepo.setDarkMode(enabled) }
        }
    }
}
