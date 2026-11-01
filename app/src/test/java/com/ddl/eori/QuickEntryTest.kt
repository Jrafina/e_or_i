package com.ddl.eori

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.local.TransactionEntity
import com.ddl.eori.data.local.TxType
import com.ddl.eori.data.remote.AiClient
import com.ddl.eori.data.local.EoriDatabase
import com.ddl.eori.data.repo.AccountRepository
import com.ddl.eori.data.repo.TransactionRepository
import com.ddl.eori.data.settings.AiConfig
import com.ddl.eori.ui.home.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 首页快捷记账的端到端测试（拦截器伪造 OpenAI 响应，不发起真实网络请求）。
 *
 * 覆盖本轮需求：
 * 1. AI 解析成功后**直接入账**，无需二次确认；
 * 2. 入账同时更新账户余额，撤销后还原；
 * 3. 解析失败时降级手动表单，输入不丢失。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuickEntryTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun app() = ApplicationProvider.getApplicationContext<EoriApplication>()

    private fun quote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** 伪造 chat/completions 响应；content 为模型输出的正文 */
    private fun fakeClient(status: Int, content: String): AiClient {
        val body = """{"choices":[{"message":{"role":"assistant","content":${quote(content)}}}]}"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 200) "OK" else "ERR")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return AiClient(http)
    }

    private fun viewModel(client: AiClient): HomeViewModel {
        val c = app().container
        return HomeViewModel(
            txRepo = c.transactionRepository,
            accountRepo = c.accountRepository,
            settingsRepo = c.settingsRepository,
            aiClient = client,
        )
    }

    private suspend fun prepareConfig() {
        val container = app().container
        container.settingsRepository.saveAiConfig(
            AiConfig(baseUrl = "https://example.com/v1", apiKey = "sk-test", model = "test-model")
        )
        container.accountRepository.ensureDefaults()
        // 测试间共享同一个数据库，前面用例可能删过账户，这里显式补齐
        if (container.accountRepository.all().none { it.type == AccountType.ALIPAY }) {
            container.accountRepository.create("支付宝", AccountType.ALIPAY, null)
        }
    }

    /** 轮询等待条件成立（真实 IO 线程不受虚拟时间控制） */
    private suspend fun awaitUntil(timeoutMs: Long = 10_000, condition: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(50)
        }
        return condition()
    }

    private suspend fun recent(): List<TransactionEntity> =
        app().container.transactionRepository.observeRecent(500).first()

    private fun alipayBalance(): Long? =
        runBlocking { app().container.accountRepository.all().first { it.name == "支付宝" }.balanceCents }

    @Test
    fun aiResultIsSavedImmediatelyWithoutConfirmation() = runBlocking {
        prepareConfig()
        val container = app().container
        val countBefore = recent().size
        val balanceBefore = alipayBalance() ?: 0L

        val modelOutput = """{"amount":20,"type":"expense","account":"支付宝","note":"吃饭","occurredAt":"2026-10-30T12:00:00"}"""
        val vm = viewModel(fakeClient(200, modelOutput))

        // 保持 uiState 有订阅者（stateIn WhileSubscribed）
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job: Job = scope.launch { vm.uiState.collect {} }

        vm.onInputChange("吃饭花了支付宝20元")
        vm.submit()

        val savedOk = awaitUntil { recent().size == countBefore + 1 }
        assertTrue("解析成功应直接新增一笔账单（无需确认）", savedOk)

        val latest = recent().first()
        assertEquals(2000L, latest.amountCents)
        assertEquals(TxType.EXPENSE, latest.type)
        assertEquals("支付宝", latest.accountName)
        assertEquals("吃饭", latest.note)

        // 未确认即入账：草稿为空、输入已清空、提供撤销入口
        assertNull("不应再弹出确认卡片", vm.uiState.value.draft)
        assertEquals("入账后应清空输入框", "", vm.uiState.value.input)
        assertTrue("应提供撤销入口", vm.uiState.value.canUndo)

        // 余额联动：支出 20 元后余额减少 20 元
        assertEquals(balanceBefore - 2000L, alipayBalance())

        // 撤销：账单消失、余额还原
        vm.undoLastAdded()
        assertTrue("撤销后账单应消失", awaitUntil { recent().size == countBefore })
        assertEquals(balanceBefore, alipayBalance())

        job.cancel()
    }

    /**
     * 回归测试：用户有两张卡时，说「卡2消费2元」必须记到卡2，而不是卡1。
     *
     * 使用独立的内存数据库，避免与其他用例共享数据库造成的顺序依赖。
     */
    @Test
    fun secondCardIsCreditedNotFirstCard() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val memDb = androidx.room.Room.inMemoryDatabaseBuilder(context, EoriDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val accountRepo = AccountRepository(memDb.accountDao(), memDb.transactionDao())
            val txRepo = TransactionRepository(memDb)
            accountRepo.create("卡1", AccountType.BANK, 100_000L)
            accountRepo.create("卡2", AccountType.BANK, 100_000L)

            val settingsRepo = app().container.settingsRepository
            settingsRepo.saveAiConfig(
                AiConfig(baseUrl = "https://example.com/v1", apiKey = "sk-test", model = "test-model")
            )

            // 故意让模型返回模糊的“银行卡”，验证本地兜底能根据原话纠正到卡2
            val modelOutput = """{"amount":2,"type":"expense","account":"银行卡","note":"消费","occurredAt":"2026-10-30T12:00:00"}"""
            val vm = HomeViewModel(
                txRepo = txRepo,
                accountRepo = accountRepo,
                settingsRepo = settingsRepo,
                aiClient = fakeClient(200, modelOutput),
            )
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val job = scope.launch { vm.uiState.collect {} }

            vm.onInputChange("卡2消费2元")
            vm.submit()

            assertTrue("应新增一笔账单", awaitUntil { txRepo.observeRecent(10).first().isNotEmpty() })
            val latest = txRepo.observeRecent(10).first().first()
            assertEquals("卡2", latest.accountName)
            assertEquals(200L, latest.amountCents)

            val card2 = accountRepo.all().first { it.name == "卡2" }
            val card1 = accountRepo.all().first { it.name == "卡1" }
            assertEquals("卡2 余额应减少 2 元", 99_800L, card2.balanceCents)
            assertEquals("卡1 余额不应变化", 100_000L, card1.balanceCents)

            job.cancel()
        } finally {
            memDb.close()
        }
    }

    @Test
    fun parseFailureFallsBackToManualForm() = runBlocking {
        prepareConfig()
        val vm = viewModel(fakeClient(401, "unauthorized"))
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { vm.uiState.collect {} }

        vm.onInputChange("吃饭花了支付宝20元")
        vm.submit()

        val fellBack = awaitUntil { vm.uiState.value.draft != null }
        assertTrue("解析失败应降级手动表单", fellBack)
        assertNotNull(vm.uiState.value.draft)
        assertEquals("失败时输入内容不应丢失", "吃饭花了支付宝20元", vm.uiState.value.input)
        assertTrue(
            "失败提示应说明鉴权问题",
            vm.uiState.value.message?.contains("鉴权") == true,
        )

        job.cancel()
        // 避免影响后续用例的提示状态
        vm.clearMessage()
    }

    @Test
    fun submitWithBlankInputDoesNotCallAi() = runBlocking {
        val countBefore = recent().size
        val vm = viewModel(fakeClient(200, """{"amount":1,"type":"expense"}"""))
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { vm.uiState.collect {} }

        vm.onInputChange("   ")
        vm.submit()
        Thread.sleep(300)

        assertEquals("空输入不应产生账单", countBefore, recent().size)
        assertNotNull("空输入应提示用户", vm.uiState.value.message)
        job.cancel()
        vm.clearMessage()
    }
}
