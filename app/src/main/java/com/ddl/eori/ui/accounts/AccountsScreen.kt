package com.ddl.eori.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.local.AccountWithCount
import com.ddl.eori.domain.label
import com.ddl.eori.ui.components.EmptyHint
import com.ddl.eori.ui.components.accountColor
import com.ddl.eori.util.Money

@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onCreate: () -> Unit,
    onEdit: (AccountWithCount) -> Unit,
    onRequestDelete: (AccountWithCount) -> Unit,
    onEditorChange: ((AccountEditor) -> AccountEditor) -> Unit,
    onEditorSave: () -> Unit,
    onEditorDismiss: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = "添加账户")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                text = "账户",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            Text(
                text = "删除账户不会删除历史账单",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )

            TotalBalanceCard(
                totalCents = state.totalBalanceCents,
                accountCount = state.accounts.size,
            )

            if (state.accounts.isEmpty()) {
                EmptyHint("还没有账户，点击右下角添加")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.accounts, key = { it.id }) { account ->
                        AccountRow(
                            account = account,
                            onEdit = { onEdit(account) },
                            onDelete = { onRequestDelete(account) },
                        )
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    state.editor?.let { editor ->
        AccountEditorDialog(
            editor = editor,
            onChange = onEditorChange,
            onSave = onEditorSave,
            onDismiss = onEditorDismiss,
        )
    }

    state.pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = onDeleteCancel,
            title = { Text("删除账户") },
            text = {
                Text(
                    if (target.txCount > 0) {
                        "确定删除「${target.name}」吗？该账户下有 ${target.txCount} 笔账单，账单会保留并显示为「已删除账户」。"
                    } else {
                        "确定删除「${target.name}」吗？"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = onDeleteConfirm) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = onDeleteCancel) { Text("取消") }
            },
        )
    }
}

/** 总余额卡片 */
@Composable
private fun TotalBalanceCard(totalCents: Long, accountCount: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = "总余额",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = Money.format(totalCents),
                style = MaterialTheme.typography.headlineSmall,
                color = if (totalCents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$accountCount 个账户 · 账单会自动更新余额",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountRow(
    account: AccountWithCount,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(accountColor(account.type).copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = account.name.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = accountColor(account.type),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${account.type.label()} · ${account.txCount} 笔账单" +
                        (account.balanceCents?.let { " · 余额 ${Money.format(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun AccountEditorDialog(
    editor: AccountEditor,
    onChange: ((AccountEditor) -> AccountEditor) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.isNew) "添加账户" else "编辑账户") },
        text = {
            Column {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { value -> onChange { it.copy(name = value.take(20)) } },
                    label = { Text("账户名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("类型", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AccountType.entries.forEach { type ->
                        FilterChip(
                            selected = editor.type == type,
                            onClick = {
                                onChange {
                                    it.copy(
                                        type = type,
                                        name = if (it.name.isBlank()) type.label() else it.name,
                                    )
                                }
                            },
                            label = { Text(type.label()) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = editor.balanceText,
                    onValueChange = { value ->
                        val filtered = value.filter { it.isDigit() || it == '.' }
                        if (filtered.count { it == '.' } <= 1) {
                            onChange { it.copy(balanceText = filtered) }
                        }
                    },
                    label = { Text("余额（可选，元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = editor.canSave) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
