package com.ddl.eori.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ddl.eori.data.local.AccountEntity
import com.ddl.eori.data.local.TxType
import com.ddl.eori.util.Dates
import com.ddl.eori.util.Money

/**
 * 确认卡片：AI 解析结果与手动表单共用。
 * 所有字段可编辑，确认后才写入数据库（避免 AI 误判直接落库）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftCard(
    draft: Draft,
    accounts: List<AccountEntity>,
    onDraftChange: ((Draft) -> Draft) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (draft.isEditing) "编辑账单" else "手动记账",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "取消", modifier = Modifier.width(16.dp))
                    Text("取消")
                }
            }

            // 收支类型
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.type == TxType.EXPENSE,
                    onClick = { onDraftChange { it.copy(type = TxType.EXPENSE) } },
                    label = { Text("支出") },
                )
                FilterChip(
                    selected = draft.type == TxType.INCOME,
                    onClick = { onDraftChange { it.copy(type = TxType.INCOME) } },
                    label = { Text("收入") },
                )
            }
            Spacer(Modifier.height(8.dp))

            // 金额
            OutlinedTextField(
                value = draft.amountText,
                onValueChange = { value ->
                    // 只允许数字与小数点，最多两位小数
                    val filtered = value.filter { it.isDigit() || it == '.' }
                    if (filtered.count { it == '.' } <= 1) {
                        onDraftChange { it.copy(amountText = filtered) }
                    }
                },
                label = { Text("金额（元）") },
                prefix = { Text("¥") },
                singleLine = true,
                isError = draft.amountText.isNotBlank() && !draft.isValid,
                supportingText = {
                    if (draft.amountText.isNotBlank() && !draft.isValid) {
                        Text("请输入大于 0 的金额")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            // 账户选择
            Text("账户", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (accounts.isEmpty()) {
                Text(
                    text = "暂无账户，可在「账户」页添加",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    accounts.forEach { account ->
                        FilterChip(
                            selected = draft.accountId == account.id,
                            onClick = {
                                onDraftChange {
                                    it.copy(accountId = if (it.accountId == account.id) null else account.id)
                                }
                            },
                            label = { Text(account.name) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 备注
            OutlinedTextField(
                value = draft.note,
                onValueChange = { value -> onDraftChange { it.copy(note = value.take(40)) } },
                label = { Text("备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            // 时间
            AssistChip(
                onClick = { showDatePicker = true },
                label = { Text("时间：${Dates.formatDateTime(draft.occurredAt)}") },
            )

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onConfirm, enabled = draft.isValid) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.width(16.dp))
                    Text("确认保存")
                }
            }
        }
    }

    if (showDatePicker) {
        val initial = draft.occurredAt
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = pickerState.selectedDateMillis
                    if (picked != null) {
                        // DatePicker 返回 UTC 零点，保留原时间的时分
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = initial }
                        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                        val minute = cal.get(java.util.Calendar.MINUTE)
                        val newCal = java.util.Calendar.getInstance().apply {
                            timeInMillis = picked
                            set(java.util.Calendar.HOUR_OF_DAY, hour)
                            set(java.util.Calendar.MINUTE, minute)
                        }
                        onDraftChange { it.copy(occurredAt = newCal.timeInMillis) }
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
