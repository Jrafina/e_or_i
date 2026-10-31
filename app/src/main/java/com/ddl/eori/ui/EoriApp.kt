package com.ddl.eori.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ddl.eori.ui.accounts.AccountsScreen
import com.ddl.eori.ui.accounts.AccountsViewModel
import com.ddl.eori.ui.home.HomeScreen
import com.ddl.eori.ui.home.HomeViewModel
import com.ddl.eori.ui.settings.SettingsScreen
import com.ddl.eori.ui.settings.SettingsViewModel
import com.ddl.eori.ui.stats.StatsScreen
import com.ddl.eori.ui.stats.StatsViewModel
import com.ddl.eori.ui.components.AppIcons

/** Snackbar 事件：文案 + 来源页面（用于精确清理）+ 是否提供“撤销” */
private data class SnackbarEvent(val text: String, val source: String, val canUndo: Boolean = false)

/** 底部导航的 4 个目的地 */
enum class Destination(val label: String) {
    HOME("首页"),
    STATS("统计"),
    ACCOUNTS("账户"),
    SETTINGS("设置"),
}

/**
 * 单 Activity + 单 Scaffold + 底部导航。
 * 用 when 切换 Compose（不引入 Navigation 库）：4 个平级页面无需路由参数，
 * 既减少依赖体积，也让 ViewModel 在 Activity 级存活、旋转不丢状态。
 */
@Composable
fun EoriApp() {
    var current by rememberSaveable { mutableStateOf(Destination.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 各页面 ViewModel 在 Activity 级创建一次，切换 Tab 不销毁（保留输入与滚动状态）
    val homeViewModel: HomeViewModel = viewModel(factory = AppViewModelFactory.Factory)
    val statsViewModel: StatsViewModel = viewModel(factory = AppViewModelFactory.Factory)
    val accountsViewModel: AccountsViewModel = viewModel(factory = AppViewModelFactory.Factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory.Factory)

    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val statsState by statsViewModel.uiState.collectAsStateWithLifecycle()
    val accountsState by accountsViewModel.uiState.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    // 统一 Snackbar 反馈：只清理产生该消息的页面，避免误清其它页面的提示
    val snackbarEvent: SnackbarEvent? =
        homeState.message?.let { SnackbarEvent(it, "home", canUndo = homeState.canUndo) }
            ?: accountsState.message?.let { SnackbarEvent(it, "accounts") }
            ?: settingsState.message?.let { SnackbarEvent(it, "settings") }
    LaunchedEffect(snackbarEvent) {
        val event = snackbarEvent ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = event.text,
            actionLabel = if (event.canUndo) "撤销" else null,
            duration = if (event.canUndo) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && event.canUndo) {
            // AI 直接入账没有二次确认，给用户一个后悔入口
            homeViewModel.undoLastAdded()
        }
        when (event.source) {
            "home" -> homeViewModel.clearMessage()
            "accounts" -> accountsViewModel.consumeMessage()
            "settings" -> settingsViewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = current == destination,
                        onClick = { current = destination },
                        icon = {
                            Icon(
                                imageVector = when (destination) {
                                    Destination.HOME -> Icons.AutoMirrored.Filled.List
                                    Destination.STATS -> AppIcons.BarChart
                                    Destination.ACCOUNTS -> AppIcons.Wallet
                                    Destination.SETTINGS -> Icons.Default.Settings
                                },
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (current) {
                Destination.HOME -> HomeScreen(
                    state = homeState,
                    onInputChange = homeViewModel::onInputChange,
                    onSubmit = homeViewModel::submit,
                    onManualEntry = { homeViewModel.openManualDraft() },
                    onDraftChange = homeViewModel::updateDraft,
                    onDraftConfirm = homeViewModel::confirmDraft,
                    onDraftDismiss = homeViewModel::dismissDraft,
                    onEditTransaction = { id ->
                        // 编辑：取出账单填入草稿
                        homeViewModel.onEditFromList(id)
                    },
                    onDeleteTransaction = homeViewModel::deleteTransaction,
                    onGoSettings = { current = Destination.SETTINGS },
                )
                Destination.STATS -> StatsScreen(
                    state = statsState,
                    onPreviousMonth = statsViewModel::previousMonth,
                    onNextMonth = statsViewModel::nextMonth,
                )
                Destination.ACCOUNTS -> AccountsScreen(
                    state = accountsState,
                    onCreate = accountsViewModel::startCreate,
                    onEdit = accountsViewModel::startEdit,
                    onRequestDelete = accountsViewModel::requestDelete,
                    onEditorChange = accountsViewModel::updateEditor,
                    onEditorSave = accountsViewModel::saveEditor,
                    onEditorDismiss = accountsViewModel::dismissEditor,
                    onDeleteConfirm = accountsViewModel::confirmDelete,
                    onDeleteCancel = accountsViewModel::cancelDelete,
                )
                Destination.SETTINGS -> SettingsScreen(
                    state = settingsState,
                    onBaseUrlChange = settingsViewModel::onBaseUrlChange,
                    onApiKeyChange = settingsViewModel::onApiKeyChange,
                    onModelChange = settingsViewModel::onModelChange,
                    onToggleKeyVisible = settingsViewModel::toggleKeyVisible,
                    onVerify = settingsViewModel::verify,
                    onSave = settingsViewModel::save,
                    onDarkModeChange = settingsViewModel::setDarkMode,
                )
            }
        }
    }
}
