package com.ddl.eori.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ddl.eori.util.Dates
import com.ddl.eori.ui.components.AppIcons

/**
 * 设置页：AI 配置 + 外观。
 * API Key 默认掩码展示，可切换显示；配置只保存在本地 DataStore。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onToggleKeyVisible: () -> Unit,
    onVerify: () -> Unit,
    onSave: () -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onSyncEndpointChange: (String) -> Unit,
    onSyncTokenChange: (String) -> Unit,
    onToggleSyncTokenVisible: () -> Unit,
    onSyncTest: () -> Unit,
    onSyncNow: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("设置", style = MaterialTheme.typography.titleLarge)

        Spacer(Modifier.height(16.dp))
        Text("AI 解析", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "兼容 OpenAI 接口格式（如 OpenAI / DeepSeek / 通义千问 / 自建网关）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("Base URL") },
            placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = { Text("带不带 /v1 都可以，会自动补全") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("API Key") },
            placeholder = { Text("sk-...") },
            singleLine = true,
            visualTransformation = if (state.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = onToggleKeyVisible) {
                    Icon(
                        imageVector = if (state.keyVisible) AppIcons.VisibilityOff else AppIcons.Visibility,
                        contentDescription = if (state.keyVisible) "隐藏" else "显示",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onVerify,
                enabled = state.canVerify && state.verifyState !is VerifyState.Loading,
                modifier = Modifier.weight(1f),
            ) {
                if (state.verifyState is VerifyState.Loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (state.verifyState is VerifyState.Loading) "验证中…" else "验证")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
            ) {
                Text("保存配置")
            }
        }

        when (val verify = state.verifyState) {
            is VerifyState.Success -> {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "验证通过，共 ${verify.models.size} 个模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            is VerifyState.Failure -> {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = verify.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> Unit
        }

        Spacer(Modifier.height(12.dp))

        // 模型下拉选择（验证成功后可用）
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (state.models.isNotEmpty()) expanded = it },
        ) {
            OutlinedTextField(
                value = state.model,
                onValueChange = {},
                readOnly = true,
                label = { Text("模型") },
                placeholder = { Text(if (state.models.isEmpty()) "请先验证后选择" else "选择模型") },
                trailingIcon = {
                    if (state.models.isEmpty()) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    } else {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                state.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model, maxLines = 1) },
                        onClick = {
                            onModelChange(model)
                            expanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // ------------------------------------------------------------ 云同步
        Text("云同步", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "手动同步到 Cloudflare D1。按两端最后更新时间决定方向，只传增量。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "最近同步：${state.lastSyncAt?.let { Dates.formatDateTime(it) } ?: "从未同步"}",
            style = MaterialTheme.typography.bodySmall,
            color = if (state.lastSyncAt != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.lastSyncSummary != null) {
            Text(
                text = state.lastSyncSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.syncEndpoint,
            onValueChange = onSyncEndpointChange,
            label = { Text("同步地址（Worker URL）") },
            placeholder = { Text("https://eori-sync.xxx.workers.dev") },
            singleLine = true,
            isError = !state.syncSecure,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = {
                Text(
                    text = if (state.syncSecure) "必须是 https:// 地址" else "明文 http 会泄露账单，请改用 https",
                    color = if (state.syncSecure) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = state.syncToken,
            onValueChange = onSyncTokenChange,
            label = { Text("同步密钥（SYNC_TOKEN）") },
            placeholder = { Text("部署 Worker 时设置的密钥") },
            singleLine = true,
            visualTransformation = if (state.syncTokenVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = onToggleSyncTokenVisible) {
                    Icon(
                        imageVector = if (state.syncTokenVisible) AppIcons.VisibilityOff else AppIcons.Visibility,
                        contentDescription = if (state.syncTokenVisible) "隐藏" else "显示",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onSyncTest,
                enabled = !state.syncRunning,
                modifier = Modifier.weight(1f),
            ) { Text("测试连接") }
            Button(
                onClick = onSyncNow,
                enabled = !state.syncRunning && state.syncEndpoint.isNotBlank() && state.syncToken.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                if (state.syncRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (state.syncRunning) "同步中…" else "立即同步")
            }
        }

        if (state.syncError != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.syncError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text("外观", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "默认跟随系统，可手动固定",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.darkMode, onCheckedChange = onDarkModeChange)
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "e or i · 一句话记账\n所有数据仅保存在本机，API Key 不会上传到第三方服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
