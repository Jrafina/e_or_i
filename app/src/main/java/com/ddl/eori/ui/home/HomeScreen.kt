package com.ddl.eori.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ddl.eori.ui.components.EmptyHint
import com.ddl.eori.ui.components.SectionHeader
import com.ddl.eori.ui.components.TransactionRow
import com.ddl.eori.ui.components.accountColor
import kotlinx.coroutines.delay
import com.ddl.eori.ui.components.AppIcons

/**
 * 首页：一句话快速记账。
 *
 * 交互顺序：自动聚焦输入 -> 发送 -> 解析中 -> 确认卡片 -> 保存。
 * 未配置 AI / 解析失败时自动切到手动表单（同一个确认卡片），保证记账不被阻断。
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onManualEntry: () -> Unit,
    onDraftChange: ((Draft) -> Draft) -> Unit,
    onDraftConfirm: () -> Unit,
    onDraftDismiss: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onDeleteTransaction: (Long) -> Unit,
    onGoSettings: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // 首帧自动聚焦并弹出键盘（延迟一帧，等 Compose 布局完成）
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    val parsing = state.parseState is ParseUiState.Parsing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "e or i",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "说一句话，记一笔账",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                placeholder = { Text("例如：吃饭花了支付宝20元") },
                // 单行：IME 的“发送/回车”键才能稳定触发解析（多行时回车会插入换行）
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    // 外接键盘 / 部分输入法的实体回车键
                    .onPreviewKeyEvent { event ->
                        val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (enter && event.type == KeyEventType.KeyDown && state.input.isNotBlank() && !parsing) {
                            focusManager.clearFocus()
                            onSubmit()
                            true
                        } else {
                            false
                        }
                    },
                trailingIcon = {
                    if (parsing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            onSubmit()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    focusManager.clearFocus()
                    onSubmit()
                }),
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (!state.aiReady) {
                    TextButton(onClick = onGoSettings) {
                        Icon(AppIcons.Sparkle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("去配置 AI", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Text(
                        text = "AI 已就绪",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onManualEntry) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("手动记账")
                }
            }

            if (parsing) {
                Text(
                    text = "正在解析…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
        }

        AnimatedVisibility(visible = state.draft != null) {
            state.draft?.let { draft ->
                DraftCard(
                    draft = draft,
                    accounts = state.accounts,
                    onDraftChange = onDraftChange,
                    onConfirm = onDraftConfirm,
                    onDismiss = onDraftDismiss,
                )
            }
        }

        SectionHeader(
            text = "最近账单",
            trailing = {
                Text(
                    text = "${state.recent.size} 笔",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        if (state.recent.isEmpty()) {
            EmptyHint("还没有账单，先说一句试试")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.recent, key = { it.id }) { tx ->
                    TransactionRow(
                        tx = tx,
                        knownAccountIds = state.knownAccountIds,
                        onEdit = { onEditTransaction(tx.id) },
                        onDelete = { onDeleteTransaction(tx.id) },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
