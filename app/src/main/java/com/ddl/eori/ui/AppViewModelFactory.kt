package com.ddl.eori.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ddl.eori.EoriApplication
import com.ddl.eori.ui.accounts.AccountsViewModel
import com.ddl.eori.ui.home.HomeViewModel
import com.ddl.eori.ui.settings.SettingsViewModel
import com.ddl.eori.ui.stats.StatsViewModel

/**
 * 手动注入的 ViewModel 工厂。集中在一处，避免每个 Screen 重复写 Factory。
 */
object AppViewModelFactory {

    private fun CreationExtras.container() =
        (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EoriApplication).container

    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val c = container()
            HomeViewModel(
                txRepo = c.transactionRepository,
                accountRepo = c.accountRepository,
                settingsRepo = c.settingsRepository,
                aiClient = c.aiClient,
            )
        }
        initializer {
            StatsViewModel(txRepo = container().transactionRepository)
        }
        initializer {
            val c = container()
            AccountsViewModel(
                accountRepo = c.accountRepository,
                txRepo = c.transactionRepository,
            )
        }
        initializer {
            val c = container()
            SettingsViewModel(
                settingsRepo = c.settingsRepository,
                aiClient = c.aiClient,
                syncEngine = c.syncEngine,
            )
        }
    }

    /** 按类型取工厂（Compose viewModel() 会用类型匹配） */
    inline fun <reified VM : ViewModel> factory(): ViewModelProvider.Factory = Factory
}
