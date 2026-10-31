package com.ddl.eori.di

import android.content.Context
import com.ddl.eori.data.local.EoriDatabase
import com.ddl.eori.data.remote.AiClient
import com.ddl.eori.data.repo.AccountRepository
import com.ddl.eori.data.repo.TransactionRepository
import com.ddl.eori.data.settings.SettingsRepository

/**
 * 极简手动注入容器（替代 Hilt）。
 *
 * - 单例在 Application 创建，所有 ViewModel 通过 [AppContainer] 取依赖；
 * - 数据库采用懒加载，首屏渲染不阻塞于 Room 初始化；
 * - 不持有 Activity/Context 引用，避免内存泄漏。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: EoriDatabase by lazy { EoriDatabase.get(appContext) }

    val transactionRepository: TransactionRepository by lazy {
        TransactionRepository(database)
    }

    val accountRepository: AccountRepository by lazy {
        AccountRepository(database.accountDao(), database.transactionDao())
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val aiClient: AiClient by lazy { AiClient() }
}
