package com.ddl.eori

import android.app.Application
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.local.TxType
import com.ddl.eori.data.remote.ApiUrls
import com.ddl.eori.data.remote.ModelListParser
import com.ddl.eori.data.settings.AiConfig
import com.ddl.eori.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric 冒烟测试：在 JVM 上真实启动 MainActivity，
 * 验证「应用能启动、Compose 首屏能渲染、Room/DataStore 全链路可用」。
 *
 * 说明：本机没有 KVM，无法运行 Android 模拟器，因此用 Robolectric 覆盖
 * 启动崩溃、依赖注入、数据库读写等最容易出问题的部分。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppLaunchTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun applicationStartsAndExposesContainer() {
        val app = context as Application
        assertTrue("Application 应为 EoriApplication", app is EoriApplication)
        val container = (app as EoriApplication).container
        assertNotNull("容器应已初始化", container)
        assertNotNull(container.transactionRepository)
        assertNotNull(container.accountRepository)
        assertNotNull(container.settingsRepository)
        assertNotNull(container.aiClient)
    }

    @Test
    fun mainActivityLaunchesWithoutCrash() {
        // 能正常 create/resume 且不抛异常，即代表首屏（含输入框、底部导航）渲染成功
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun accountAndTransactionPersistenceWorks() = runBlocking {
        val container = (context as EoriApplication).container

        // 首次启动预置账户（Application.onCreate 异步执行，这里显式确保完成）
        container.accountRepository.ensureDefaults()
        val accounts = container.accountRepository.all()
        assertTrue("应预置常用账户", accounts.size >= 4)

        val alipay = accounts.first { it.type == AccountType.ALIPAY }
        val id = container.transactionRepository.add(
            amountCents = 2000,
            type = TxType.EXPENSE,
            account = alipay,
            note = "吃饭",
            rawInput = "吃饭花了支付宝20元",
            occurredAt = System.currentTimeMillis(),
        )
        assertTrue(id > 0)

        val saved = container.transactionRepository.get(id)
        assertNotNull(saved)
        assertEquals(2000L, saved!!.amountCents)
        assertEquals(TxType.EXPENSE, saved.type)
        assertEquals("支付宝", saved.accountName)

        // 删除账户后账单仍保留（不级联删除）
        container.accountRepository.delete(alipay.id)
        val afterDelete = container.transactionRepository.get(id)
        assertNotNull("删除账户不应删除账单", afterDelete)
        assertNull(container.accountRepository.get(alipay.id))
    }

    @Test
    fun ledgerKeepsAccountBalanceInSync() = runBlocking {
        val container = (context as EoriApplication).container
        val accountRepo = container.accountRepository
        val txRepo = container.transactionRepository

        // 初始余额 1000.00 元
        val accountId = accountRepo.create("测试卡", AccountType.BANK, 100_000L)
        val account = accountRepo.get(accountId)!!

        // 支出 20 元 -> 980.00
        val expenseId = txRepo.add(2_000, TxType.EXPENSE, account, "午饭", "午饭20元", System.currentTimeMillis())
        assertEquals(98_000L, accountRepo.get(accountId)!!.balanceCents)

        // 收入 5 元 -> 985.00
        val incomeId = txRepo.add(500, TxType.INCOME, account, "红包", "红包5元", System.currentTimeMillis())
        assertEquals(98_500L, accountRepo.get(accountId)!!.balanceCents)

        // 改金额：支出 20 -> 30，先撤销旧的 20 再扣 30 => 1000 - 30 + 5 = 975.00
        val savedExpense = txRepo.get(expenseId)!!
        txRepo.update(savedExpense.copy(amountCents = 3_000))
        assertEquals(97_500L, accountRepo.get(accountId)!!.balanceCents)

        // 删除收入 5 元 -> 970.00
        txRepo.delete(incomeId)
        assertEquals(97_000L, accountRepo.get(accountId)!!.balanceCents)

        // 删除支出 30 元 -> 恢复初始 1000.00
        txRepo.delete(expenseId)
        assertEquals(100_000L, accountRepo.get(accountId)!!.balanceCents)
    }

    @Test
    fun editingTransactionAccountMovesBalance() = runBlocking {
        val container = (context as EoriApplication).container
        val accountRepo = container.accountRepository
        val txRepo = container.transactionRepository

        val fromId = accountRepo.create("转出卡", AccountType.BANK, 10_000L)
        val toId = accountRepo.create("转入卡", AccountType.BANK, 5_000L)
        val from = accountRepo.get(fromId)!!
        val to = accountRepo.get(toId)!!

        val txId = txRepo.add(1_000, TxType.EXPENSE, from, "咖啡", "咖啡10元", System.currentTimeMillis())
        assertEquals(9_000L, accountRepo.get(fromId)!!.balanceCents)

        // 换账户：原账户退回，新账户扣除
        val tx = txRepo.get(txId)!!
        txRepo.update(tx.copy(accountId = to.id, accountName = to.name))
        assertEquals(10_000L, accountRepo.get(fromId)!!.balanceCents)
        assertEquals(4_000L, accountRepo.get(toId)!!.balanceCents)
    }

    @Test
    fun settingsPersistAiConfig() = runBlocking {
        val container = (context as EoriApplication).container
        val config = AiConfig(
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "sk-test-key",
            model = "deepseek-chat",
        )
        container.settingsRepository.saveAiConfig(config)

        val loaded = container.settingsRepository.aiConfig.first()
        assertEquals("https://api.deepseek.com/v1", loaded.baseUrl)
        assertEquals("deepseek-chat", loaded.model)
        assertTrue(loaded.isReady)
    }

    @Test
    fun aiConfigWithoutModelIsNotReady() {
        assertFalse(AiConfig("https://x.com", "sk-1", "").isReady)
        assertFalse(AiConfig("", "sk-1", "m").isReady)
        assertTrue(AiConfig("https://x.com", "sk-1", "m").isReady)
    }

    @Test
    fun urlNormalizationHandlesCommonUserInputs() {
        assertEquals("https://api.openai.com/v1/models", ApiUrls.modelsUrl("https://api.openai.com"))
        assertEquals("https://api.openai.com/v1/models", ApiUrls.modelsUrl("https://api.openai.com/v1/"))
        assertEquals("https://api.deepseek.com/v1/models", ApiUrls.modelsUrl("api.deepseek.com"))
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            ApiUrls.chatCompletionsUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
    }

    @Test
    fun modelListParsingHandlesRealWorldResponses() {
        val openAi = """{"object":"list","data":[{"id":"gpt-4o-mini","object":"model"},{"id":"gpt-4o","object":"model"}]}"""
        assertEquals(listOf("gpt-4o-mini", "gpt-4o"), ModelListParser.parse(openAi))

        val qwen = """{"data":[{"id":"qwen-plus"},{"id":"qwen-turbo"}]}"""
        assertEquals(2, ModelListParser.parse(qwen).size)

        // 非 OpenAI 兼容（HTML 错误页）应返回空列表，由上层报“格式不正确”
        assertTrue(ModelListParser.parse("<!DOCTYPE html><html>404</html>").isEmpty())
    }

    @Test
    fun containerIsSingletonAcrossAccess() {
        val app = context as EoriApplication
        val c1: AppContainer = app.container
        val c2: AppContainer = app.container
        assertTrue("容器应为同一实例", c1 === c2)
        assertTrue("仓库应为懒加载单例", c1.transactionRepository === c2.transactionRepository)
    }
}
