package com.ddl.eori.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ddl.eori.data.local.AccountEntity
import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.local.AccountWithCount
import com.ddl.eori.data.repo.AccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 账户编辑弹窗状态 */
data class AccountEditor(
    val id: Long = 0,
    val name: String = "",
    val type: AccountType = AccountType.ALIPAY,
    val balanceText: String = "",
    val isNew: Boolean = true,
) {
    val canSave: Boolean get() = name.isNotBlank()
}

data class AccountsUiState(
    val accounts: List<AccountWithCount> = emptyList(),
    /** 总余额 = 所有账户余额之和（未填写余额的账户按 0 计） */
    val totalBalanceCents: Long = 0,
    val editor: AccountEditor? = null,
    val pendingDelete: AccountWithCount? = null,
    val message: String? = null,
)

class AccountsViewModel(
    private val accountRepo: AccountRepository,
) : ViewModel() {

    private val _editor = MutableStateFlow<AccountEditor?>(null)
    private val _pendingDelete = MutableStateFlow<AccountWithCount?>(null)
    private val _message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AccountsUiState> = combine(
        accountRepo.observeAccountsWithCount(),
        _editor,
        _pendingDelete,
        _message,
    ) { accounts, editor, pendingDelete, message ->
        AccountsUiState(
            accounts = accounts,
            totalBalanceCents = accounts.sumOf { it.balanceCents ?: 0L },
            editor = editor,
            pendingDelete = pendingDelete,
            message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun startCreate() {
        _editor.value = AccountEditor(isNew = true)
    }

    fun startEdit(account: AccountWithCount) {
        _editor.value = AccountEditor(
            id = account.id,
            name = account.name,
            type = account.type,
            balanceText = account.balanceCents?.let { com.ddl.eori.util.Money.formatPlain(it) } ?: "",
            isNew = false,
        )
    }

    fun updateEditor(transform: (AccountEditor) -> AccountEditor) {
        _editor.value = _editor.value?.let(transform)
    }

    fun dismissEditor() {
        _editor.value = null
    }

    fun saveEditor() {
        val editor = _editor.value ?: return
        if (!editor.canSave) {
            _message.value = "账户名不能为空"
            return
        }
        viewModelScope.launch {
            runCatching {
                val balance = if (editor.balanceText.isBlank()) {
                    null
                } else {
                    com.ddl.eori.util.Money.parseToCents(editor.balanceText)
                        ?: throw IllegalArgumentException("余额格式不正确")
                }
                if (editor.isNew) {
                    accountRepo.create(editor.name, editor.type, balance)
                } else {
                    val existing = accountRepo.get(editor.id)
                        ?: throw IllegalStateException("账户不存在")
                    accountRepo.update(
                        existing.copy(
                            name = editor.name,
                            type = editor.type,
                            balanceCents = balance,
                        )
                    )
                }
            }.onSuccess {
                _editor.value = null
                _message.value = if (editor.isNew) "账户已添加" else "账户已更新"
            }.onFailure { e ->
                _message.value = "保存失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun requestDelete(account: AccountWithCount) {
        _pendingDelete.value = account
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    /** 删除账户：账单保留，不级联删除 */
    fun confirmDelete() {
        val target = _pendingDelete.value ?: return
        viewModelScope.launch {
            runCatching { accountRepo.delete(target.id) }
                .onSuccess {
                    _pendingDelete.value = null
                    _message.value = if (target.txCount > 0) {
                        "账户已删除，${target.txCount} 笔历史账单保留"
                    } else {
                        "账户已删除"
                    }
                }
                .onFailure {
                    _message.value = "删除失败"
                }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun accountTypes(): List<AccountType> = AccountType.entries
}

/** 供 UI 使用的账户实体转换（编辑时回填） */
fun AccountWithCount.toEntity(): AccountEntity = AccountEntity(
    id = id,
    name = name,
    type = type,
    balanceCents = balanceCents,
)
