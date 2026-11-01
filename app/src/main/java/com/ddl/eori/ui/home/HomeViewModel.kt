package com.ddl.eori.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ddl.eori.data.local.AccountEntity
import com.ddl.eori.data.local.TransactionEntity
import com.ddl.eori.data.local.TxType
import com.ddl.eori.data.remote.AiClient
import com.ddl.eori.data.remote.asParseError
import com.ddl.eori.data.repo.AccountRepository
import com.ddl.eori.domain.AccountMatcher
import com.ddl.eori.data.repo.TransactionRepository
import com.ddl.eori.data.settings.SettingsRepository
import com.ddl.eori.util.Money
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 表单草稿：手动记账 / 编辑已有账单（AI 解析成功会直接入账，不再走确认） */
data class Draft(
    val amountText: String = "",
    val type: TxType = TxType.EXPENSE,
    val accountId: Long? = null,
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
    val rawInput: String = "",
    /** 是否在编辑已有账单（用于卡片标题） */
    val isEditing: Boolean = false,
) {
    val amountCents: Long? get() = Money.parseToCents(amountText)
    val isValid: Boolean get() = (amountCents ?: 0) > 0
}

/** 首页输入区状态机 */
sealed interface ParseUiState {
    data object Idle : ParseUiState
    data object Parsing : ParseUiState
}

data class HomeUiState(
    val input: String = "",
    val parseState: ParseUiState = ParseUiState.Idle,
    val draft: Draft? = null,
    val aiReady: Boolean = false,
    val accounts: List<AccountEntity> = emptyList(),
    val recent: List<TransactionEntity> = emptyList(),
    val knownAccountIds: Set<Long> = emptySet(),
    val message: String? = null,
    /** 最近一笔由 AI 直接入账的账单，可撤销 */
    val canUndo: Boolean = false,
)

class HomeViewModel(
    private val txRepo: TransactionRepository,
    private val accountRepo: AccountRepository,
    private val settingsRepo: SettingsRepository,
    private val aiClient: AiClient,
) : ViewModel() {

    private val _input = MutableStateFlow("")
    private val _parseState = MutableStateFlow<ParseUiState>(ParseUiState.Idle)
    private val _draft = MutableStateFlow<Draft?>(null)
    private val _message = MutableStateFlow<String?>(null)
    private val _lastAddedId = MutableStateFlow<Long?>(null)
    private var parseJob: Job? = null

    /** 输入区状态（不含数据库数据），避免每次输入都重新订阅数据库 */
    private data class InputState(
        val input: String,
        val parseState: ParseUiState,
        val draft: Draft?,
        val message: String?,
        val lastAddedId: Long?,
    )

    private val inputState = combine(_input, _parseState, _draft, _message, _lastAddedId) { input, parse, draft, msg, lastAdded ->
        InputState(input, parse, draft, msg, lastAdded)
    }

    /** 数据区状态：AI 配置 + 账户 + 最近账单 */
    private data class DataState(
        val aiReady: Boolean,
        val accounts: List<AccountEntity>,
        val recent: List<TransactionEntity>,
    )

    private val dataState = combine(
        settingsRepo.aiConfig,
        accountRepo.observeAccounts(),
        txRepo.observeRecent(60),
    ) { config, accounts, recent ->
        DataState(config.isReady, accounts, recent)
    }

    val uiState: StateFlow<HomeUiState> = combine(inputState, dataState) { input, data ->
        HomeUiState(
            input = input.input,
            parseState = input.parseState,
            draft = input.draft,
            aiReady = data.aiReady,
            accounts = data.accounts,
            recent = data.recent,
            knownAccountIds = data.accounts.map { it.id }.toSet(),
            message = input.message,
            canUndo = input.lastAddedId != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    // ------------------------------------------------------------------ 输入

    fun onInputChange(value: String) {
        _input.value = value
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun notify(text: String) {
        _message.value = text
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 发送（回车或点箭头均可触发）：
     * - 有 AI 配置：解析成功后**直接入账**，并用 Snackbar 展示结果 + 撤销入口；
     * - 未配置 / 解析失败：降级手动表单，不阻断记账。
     */
    fun submit() {
        val text = _input.value.trim()
        if (text.isEmpty()) {
            notify("先输入一句话，比如「吃饭花了支付宝20元」")
            return
        }
        if (parseJob?.isActive == true) return

        _lastAddedId.value = null
        parseJob = viewModelScope.launch {
            val config = settingsRepo.aiConfig.first()
            if (!config.isReady) {
                openManualDraft(rawInput = text)
                notify("未配置 AI，已切换手动记账")
                return@launch
            }
            _parseState.value = ParseUiState.Parsing
            // 把用户真实账户名交给模型，避免“卡2”被识别成“银行卡”而落到卡1
            val accountNames = runCatching { accountRepo.all().map { it.name } }.getOrDefault(emptyList())
            val result = aiClient.parseTransaction(
                rawInput = text,
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                accountNames = accountNames,
            )
            _parseState.value = ParseUiState.Idle

            result.fold(
                onSuccess = { parsed -> saveParsed(parsed, text) },
                onFailure = { error ->
                    openManualDraft(rawInput = text)
                    notify(error.asParseError().toMessage())
                },
            )
        }
    }

    /** 解析成功：直接落库（同时更新账户余额），失败则退回手动表单 */
    private suspend fun saveParsed(parsed: com.ddl.eori.data.remote.AiParseResult, rawInput: String) {
        val allAccounts = runCatching { accountRepo.all() }.getOrDefault(emptyList())
        // 优先按 AI 给出的账户名在原话里精确匹配（卡1/卡2 不会串），
        // 匹配不到再退回按类型挑一个；完全没有账户时留空
        val account = AccountMatcher.match(allAccounts, parsed.accountHint, rawInput)
            ?: if (allAccounts.size == 1) allAccounts.first() else accountRepo.findByTypeOrFirst(parsed.accountType)
        val note = parsed.note.ifBlank { rawInput.take(20) }
        runCatching {
            txRepo.add(
                amountCents = parsed.amountCents,
                type = parsed.type,
                account = account,
                note = note,
                rawInput = rawInput,
                occurredAt = parsed.occurredAt,
            )
        }.onSuccess { id ->
            _lastAddedId.value = id
            _input.value = ""
            _draft.value = null

            val action = if (parsed.type == TxType.INCOME) "已记收入 " else "已记支出 "
            val accountLabel = account?.name ?: "其他"
            // 只有确实没匹配到用户说的那个账户时才提示
            val fallbackHint = if (account != null &&
                parsed.accountHint.isNotBlank() &&
                !account.name.equals(parsed.accountHint, ignoreCase = true) &&
                account.type != parsed.accountType
            ) {
                "（未识别到「${parsed.accountHint}」，已记入 $accountLabel）"
            } else ""
            notify(
                buildString {
                    append(action)
                    append(Money.format(parsed.amountCents))
                    append(" · ")
                    append(accountLabel)
                    if (note.isNotBlank()) {
                        append(" · ")
                        append(note)
                    }
                    append(fallbackHint)
                }
            )
        }.onFailure { e ->
            openManualDraft(rawInput = rawInput)
            notify("保存失败：${e.message ?: "未知错误"}，请手动记账")
        }
    }

    /** 撤销最近一笔自动入账的账单（AI 可能误判，给一个后悔入口） */
    fun undoLastAdded() {
        val id = _lastAddedId.value ?: return
        _lastAddedId.value = null
        viewModelScope.launch {
            runCatching { txRepo.delete(id) }
                .onSuccess { notify("已撤销上一笔") }
                .onFailure { notify("撤销失败") }
        }
    }

    /** 降级：打开手动表单草稿 */
    fun openManualDraft(rawInput: String = "") {
        if (_draft.value != null) return
        viewModelScope.launch {
            val accounts = accountRepo.all()
            _draft.value = Draft(
                amountText = "",
                type = TxType.EXPENSE,
                accountId = accounts.firstOrNull()?.id,
                note = "",
                occurredAt = System.currentTimeMillis(),
                rawInput = rawInput,
            )
        }
    }

    /** 从最近账单列表进入编辑：先取实体再填草稿 */
    fun onEditFromList(id: Long) {
        viewModelScope.launch {
            val tx = runCatching { txRepo.get(id) }.getOrNull()
            if (tx != null) openDraftForEdit(tx)
        }
    }

    fun openDraftForEdit(tx: TransactionEntity) {
        _draft.value = Draft(
            amountText = Money.formatPlain(tx.amountCents),
            type = tx.type,
            accountId = tx.accountId,
            note = tx.note,
            occurredAt = tx.occurredAt,
            rawInput = tx.rawInput,
            isEditing = true,
        )
        editingId = tx.id
    }

    private var editingId: Long? = null

    fun updateDraft(transform: (Draft) -> Draft) {
        _draft.update { it?.let(transform) }
    }

    fun dismissDraft() {
        _draft.value = null
        editingId = null
    }

    /** 确认保存（AI 结果或手动表单共用） */
    fun confirmDraft() {
        val draft = _draft.value ?: return
        val cents = draft.amountCents
        if (cents == null || cents <= 0) {
            notify("请输入大于 0 的金额")
            return
        }
        viewModelScope.launch {
            runCatching {
                val account = draft.accountId?.let { accountRepo.get(it) }
                val id = editingId
                if (id != null) {
                    val old = txRepo.get(id)
                    if (old != null) {
                        txRepo.update(
                            old.copy(
                                amountCents = cents,
                                type = draft.type,
                                accountId = account?.id,
                                accountName = account?.name,
                                note = draft.note.trim(),
                                occurredAt = draft.occurredAt,
                            )
                        )
                    }
                } else {
                    txRepo.add(
                        amountCents = cents,
                        type = draft.type,
                        account = account,
                        note = draft.note,
                        rawInput = draft.rawInput,
                        occurredAt = draft.occurredAt,
                    )
                }
            }.onSuccess {
                _draft.value = null
                editingId = null
                _input.value = ""
                _lastAddedId.value = null
                notify("已保存")
            }.onFailure { e ->
                notify("保存失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun deleteTransaction(id: Long) {
        viewModelScope.launch {
            runCatching { txRepo.delete(id) }
                .onSuccess { notify("已删除") }
                .onFailure { notify("删除失败") }
        }
    }
}
