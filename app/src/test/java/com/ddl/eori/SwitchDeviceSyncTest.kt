package com.ddl.eori

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.local.EoriDatabase
import com.ddl.eori.data.local.TxType
import com.ddl.eori.data.repo.AccountRepository
import com.ddl.eori.data.repo.TransactionRepository
import com.ddl.eori.data.sync.SyncApi
import com.ddl.eori.data.sync.SyncConfig
import com.ddl.eori.data.sync.SyncDirection
import com.ddl.eori.data.sync.SyncEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 换新手机场景的端到端同步测试（伪造 Worker 响应，不发起真实网络请求）。
 *
 * 场景：
 * 1. 旧手机把账单与余额上传到云端；
 * 2. 新手机是空的，点同步 -> 必须拉取云端全量；
 * 3. 拉下来后账户、账单、余额、账户关联都正确。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SwitchDeviceSyncTest {

    private lateinit var db: EoriDatabase
    private lateinit var accountRepo: AccountRepository
    private lateinit var txRepo: TransactionRepository

    /** 伪造的云端状态 */
    private data class FakeCloud(
        var lastUpdatedAt: Long = 0,
        var txCount: Int = 0,
        var accountCount: Int = 0,
        var accountsJson: String = "[]",
        var transactionsJson: String = "[]",
        var hasMore: Boolean = false,
        var nextSince: Long? = null,
        var nextSinceUid: String? = null,
        var pushedAccounts: String = "",
        var pushedTransactions: String = "",
    )

    private lateinit var cloud: FakeCloud

    private val config = SyncConfig(endpoint = "https://fake.workers.dev", token = "test-token")

    private fun fakeApi(cloud: FakeCloud): SyncApi {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val body: String = when {
                path.endsWith("/sync/meta") ->
                    """{"serverTime":1,"lastUpdatedAt":${cloud.lastUpdatedAt},"transactionCount":${cloud.txCount},"accountCount":${cloud.accountCount}}"""

                path.endsWith("/sync/pull") -> """
                    {"serverTime":1,"lastUpdatedAt":${cloud.lastUpdatedAt},
                     "accounts":${cloud.accountsJson},
                     "transactions":${cloud.transactionsJson},
                     "hasMore":${cloud.hasMore},
                     "nextSince":${cloud.nextSince ?: "null"},
                     "nextSinceUid":${cloud.nextSinceUid?.let { "\"$it\"" } ?: "null"}}
                """.trimIndent()

                path.endsWith("/sync/push") -> {
                    val payload = req.body?.let {
                        val buffer = Buffer()
                        it.writeTo(buffer)
                        buffer.readUtf8()
                    } ?: ""
                    if (payload.contains("\"transactions\"")) cloud.pushedTransactions = payload
                    if (payload.contains("\"accounts\"")) cloud.pushedAccounts = payload
                    """{"ok":true,"accepted":1,"serverTime":1,"lastUpdatedAt":${cloud.lastUpdatedAt}}"""
                }

                else -> "{}"
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return SyncApi(http)
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EoriDatabase::class.java,
        ).allowMainThreadQueries().build()
        accountRepo = AccountRepository(db.accountDao(), db.transactionDao())
        txRepo = TransactionRepository(db)
        cloud = FakeCloud()
    }

    @After
    fun tearDown() = db.close()

    /** 新手机（本地空）+ 云端有数据 -> 必须拉取全量 */
    @Test
    fun newDevicePullsEverythingFromCloud() = runBlocking {
        // 云端已有旧手机的数据：2 个账户 + 3 笔账单
        val t = 1_700_000_000_000L
        cloud = FakeCloud(
            lastUpdatedAt = t,
            txCount = 3,
            accountCount = 2,
            accountsJson = """
                [{"uid":"acc-card1","name":"卡1","type":"BANK","balance":980.5,"createdAt":$t,"updatedAt":$t},
                 {"uid":"acc-alipay","name":"支付宝","type":"ALIPAY","balance":120.0,"createdAt":$t,"updatedAt":$t}]
            """.trimIndent(),
            transactionsJson = """
                [{"uid":"tx1","amount":20.0,"type":"EXPENSE","accountUid":"acc-alipay","accountName":"支付宝","note":"吃饭","rawInput":"吃饭花了支付宝20元","occurredAt":$t,"createdAt":$t,"updatedAt":$t,"deleted":false},
                 {"uid":"tx2","amount":0.3,"type":"INCOME","accountUid":"acc-alipay","accountName":"支付宝","note":"视频","rawInput":"看多多视频微信收入0.3","occurredAt":$t,"createdAt":$t,"updatedAt":$t,"deleted":false},
                 {"uid":"tx3","amount":9.5,"type":"EXPENSE","accountUid":"acc-card1","accountName":"卡1","note":"咖啡","rawInput":"卡1咖啡9.5","occurredAt":$t,"createdAt":$t,"updatedAt":$t,"deleted":false}]
            """.trimIndent(),
        )

        // 本地是空的新手机
        assertTrue(accountRepo.all().isEmpty())
        assertTrue(txRepo.observeRecent(10).first().isEmpty())

        val engine = SyncEngine(db, fakeApi(cloud))
        val outcome = engine.run(config)

        assertEquals("新手机必须拉取，而不是判定为已是最新", SyncDirection.PULL, outcome.direction)
        assertEquals(2, outcome.accountsSynced)
        assertEquals(3, outcome.transactionsSynced)

        // 账户与余额都落到本地
        val accounts = accountRepo.all()
        assertEquals(2, accounts.size)
        val app = accounts.first { it.name == "支付宝" }
        assertEquals("余额应按元转换为分", 12_000L, app.balanceCents)
        val card1 = accounts.first { it.name == "卡1" }
        assertEquals(98_050L, card1.balanceCents)

        // 账单都在，且 amount 元 -> 分 正确
        val txs = txRepo.observeRecent(10).first()
        assertEquals(3, txs.size)
        val dinner = txs.first { it.note == "吃饭" }
        assertEquals(2_000L, dinner.amountCents)
        assertEquals(TxType.EXPENSE, dinner.type)

        // 账户关联正确（不能显示成“已删除账户”）
        assertNotNull("账单必须关联到本地账户", dinner.accountId)
        assertEquals(app.id, dinner.accountId)

        val coffee = txs.first { it.note == "咖啡" }
        assertEquals(card1.id, coffee.accountId)
    }

    /** 旧手机（云端空）+ 本地有数据 -> 上传 */
    @Test
    fun firstBackupPushesLocalData() = runBlocking {
        val id = accountRepo.create("卡1", AccountType.BANK, 100_000L)
        val acc = accountRepo.get(id)!!
        txRepo.add(2_000, TxType.EXPENSE, acc, "午饭", "卡1午饭20", System.currentTimeMillis())

        cloud = FakeCloud(lastUpdatedAt = 0, txCount = 0, accountCount = 0)
        val engine = SyncEngine(db, fakeApi(cloud))
        val outcome = engine.run(config)

        assertEquals("云端为空时应上传", SyncDirection.PUSH, outcome.direction)
        assertTrue("上传内容应包含账户", cloud.pushedAccounts.contains("卡1"))
        // 余额以元上传，20 元支出后为 980.00
        assertTrue("余额应按元上传为 980.0，实际: ${cloud.pushedAccounts}", cloud.pushedAccounts.contains("980.0"))
        // 账单金额为元
        assertTrue("账单金额应为元（20.0）", cloud.pushedTransactions.contains("\"amount\":20.0"))
        assertTrue("不应再上传 amountCents", !cloud.pushedTransactions.contains("amountCents"))
    }

    /**
     * 关键回归：50 元的账单上传后，云端收到的必须是 50（而不是 0.5 或 5000）。
     * 同一份数据在“上传 -> 云端存储 -> 下载”全链路上金额必须恒定。
     */
    @Test
    fun fiftyYuanStaysFiftyYuanThroughCloud() = runBlocking {
        // 本地：100 元初始余额，支出 50 元
        val id = accountRepo.create("卡1", AccountType.BANK, 10_000L) // 100.00 元
        val acc = accountRepo.get(id)!!
        txRepo.add(5_000, TxType.EXPENSE, acc, "买东西", "买东西50元", System.currentTimeMillis())

        cloud = FakeCloud(lastUpdatedAt = 0, txCount = 0, accountCount = 0)
        SyncEngine(db, fakeApi(cloud)).run(config)

        // 账单金额：5000 分 -> 50.0 元
        assertTrue(
            "账单 50 元上传后应为 50.0，实际: ${cloud.pushedTransactions}",
            Regex(""""amount"\s*:\s*50(\.0+)?""").containsMatchIn(cloud.pushedTransactions),
        )
        assertTrue("不应出现 0.5", !Regex(""""amount"\s*:\s*0\.5[^0-9]""").containsMatchIn(cloud.pushedTransactions))
        assertTrue("不应出现 5000", !cloud.pushedTransactions.contains("\"amount\":5000"))

        // 余额：10000 - 5000 = 5000 分 -> 50.0 元
        assertTrue(
            "余额应上传为 50.0，实际: ${cloud.pushedAccounts}",
            Regex(""""balance"\s*:\s*50(\.0+)?""").containsMatchIn(cloud.pushedAccounts),
        )
    }

    /** 100 元余额在不同金额下都要准确（覆盖 0.01 / 0.3 / 9.9 / 1234.56） */
    @Test
    fun variousAmountsKeepExactYuanValue() = runBlocking {
        val cases = listOf(
            1L to "0.01",
            30L to "0.3",
            990L to "9.9",
            5_000L to "50",
            123_456L to "1234.56",
        )
        for ((cents, expected) in cases) {
            val id = accountRepo.create("卡_$cents", AccountType.BANK, cents)
            val acc = accountRepo.get(id)!!
            accountRepo.update(acc.copy(balanceCents = cents))

            cloud = FakeCloud(lastUpdatedAt = 0, txCount = 0, accountCount = 0)
            SyncEngine(db, fakeApi(cloud)).run(config)

            val ok = cloud.pushedAccounts.contains("\"balance\":$expected")
            assertTrue("$cents 分应上传为 $expected 元，实际: ${cloud.pushedAccounts}", ok)
        }
    }

    /**
     * 精确复现「删除 App 后重装再同步」：
     * 重装后本地只有启动预置的 4 个空账户（余额 null、无账单），
     * 这些账户的 updatedAt 是“刚刚”，比云端任何数据都新。
     * 如果只看时间戳，会误判成“本地较新”并上传空数据，把云端冲掉。
     * 正确行为：识别出本地没有真实数据 -> 拉取云端全量。
     */
    @Test
    fun reinstallPullsCloudInsteadOfOverwriting() = runBlocking {
        // 模拟 App 启动时预置的默认账户（余额为 null，没有任何账单）
        accountRepo.create("支付宝", AccountType.ALIPAY, null)
        accountRepo.create("微信", AccountType.WECHAT, null)
        accountRepo.create("银行卡", AccountType.BANK, null)
        accountRepo.create("现金", AccountType.CASH, null)
        assertEquals("本地只有预置空账户", 4, accountRepo.all().size)

        // 云端是用户之前同步上去的真实数据（时间戳比本地预置更早，正是危险场景）
        val t = 1_700_000_000_000L
        cloud = FakeCloud(
            lastUpdatedAt = t,
            txCount = 2,
            accountCount = 1,
            accountsJson = """[{"uid":"cloud-card","name":"招商银行卡","type":"BANK","balance":500.0,"createdAt":$t,"updatedAt":$t}]""",
            transactionsJson = """
                [{"uid":"txA","amount":50.0,"type":"EXPENSE","accountUid":"cloud-card","accountName":"招商银行卡","note":"买东西","rawInput":"买东西50元","occurredAt":$t,"createdAt":$t,"updatedAt":$t,"deleted":false},
                 {"uid":"txB","amount":120.5,"type":"INCOME","accountUid":"cloud-card","accountName":"招商银行卡","note":"报销","rawInput":"报销120.5","occurredAt":$t,"createdAt":$t,"updatedAt":$t,"deleted":false}]
            """.trimIndent(),
        )

        val engine = SyncEngine(db, fakeApi(cloud))
        val outcome = engine.run(config)

        assertEquals("重装后必须拉取云端，不能上传本地空数据", SyncDirection.PULL, outcome.direction)
        assertEquals("不应上传任何数据覆盖云端", "", cloud.pushedAccounts)
        assertEquals("不应上传任何账单", "", cloud.pushedTransactions)

        // 云端数据完整落到本地
        val txs = txRepo.observeRecent(20).first()
        assertEquals(2, txs.size)
        assertEquals(5_000L, txs.first { it.note == "买东西" }.amountCents)
        assertEquals(12_050L, txs.first { it.note == "报销" }.amountCents)

        // 云端账户的余额正确
        val cloudCard = accountRepo.all().firstOrNull { it.name == "招商银行卡" }
        assertNotNull("云端账户应被拉取", cloudCard)
        assertEquals(50_000L, cloudCard!!.balanceCents)
    }

    /** 真正的换机（本地完全空）也必须拉取 */
    @Test
    fun trulyEmptyLocalPullsCloud() = runBlocking {
        val t = 1_700_000_000_000L
        cloud = FakeCloud(
            lastUpdatedAt = t,
            txCount = 1,
            accountCount = 1,
            accountsJson = """[{"uid":"a1","name":"卡1","type":"BANK","balance":88.88,"createdAt":$t,"updatedAt":$t}]""",
            transactionsJson = """[{"uid":"t1","amount":1.23,"type":"EXPENSE","accountUid":"a1","accountName":"卡1","note":"x","rawInput":"x","occurredAt":$t,"createdAt":$t,"updatedAt":$t,"deleted":false}]""",
        )
        val outcome = SyncEngine(db, fakeApi(cloud)).run(config)
        assertEquals(SyncDirection.PULL, outcome.direction)
        assertEquals(123L, txRepo.observeRecent(10).first().first().amountCents)
    }

    /** 本地有真实账单、云端更新 -> 拉取；本地更新 -> 上传 */
    @Test
    fun bothSidesHaveDataComparesTimestamp() = runBlocking {
        // 本地新增一笔真实账单（时间为当前时刻）
        val id = accountRepo.create("卡1", AccountType.BANK, 10_000L)
        val acc = accountRepo.get(id)!!
        txRepo.add(1_000, TxType.EXPENSE, acc, "本地账单", "本地10元", System.currentTimeMillis())

        // 云端更旧 -> 应上传
        cloud = FakeCloud(lastUpdatedAt = 1_600_000_000_000L, txCount = 5, accountCount = 1)
        val up = SyncEngine(db, fakeApi(cloud)).run(config)
        assertEquals("本地较新应上传", SyncDirection.PUSH, up.direction)

        // 云端更新 -> 应拉取
        cloud = FakeCloud(
            lastUpdatedAt = System.currentTimeMillis() + 60_000,
            txCount = 1,
            accountCount = 1,
            accountsJson = """[{"uid":"newacc","name":"新卡","type":"BANK","balance":1.0,"createdAt":1,"updatedAt":${System.currentTimeMillis() + 60_000}}]""",
            transactionsJson = "[]",
        )
        val down = SyncEngine(db, fakeApi(cloud)).run(config)
        assertEquals("云端较新应拉取", SyncDirection.PULL, down.direction)
    }

    /** 拉取到已存在的同名默认账户时，不应产生重复账户 */
    @Test
    fun pullMergesIntoPreexistingDefaultAccount() = runBlocking {
        // 新手机启动时会预置「支付宝」等默认账户
        accountRepo.create("支付宝", AccountType.ALIPAY, null)
        assertEquals(1, accountRepo.all().size)

        // 云端数据比本地预置账户更新（换机时云端通常是“更晚写入”的一方）
        val t = System.currentTimeMillis() + 60_000
        cloud = FakeCloud(
            lastUpdatedAt = t,
            txCount = 1,
            accountCount = 1,
            accountsJson = """[{"uid":"cloud-alipay","name":"支付宝","type":"ALIPAY","balance":50.0,"createdAt":$t,"updatedAt":$t}]""",
            transactionsJson = """[{"uid":"tx1","amount":10.0,"type":"EXPENSE","accountUid":"cloud-alipay","accountName":"支付宝","note":"x","rawInput":"x","occurredAt":$t,"createdAt":$t,"updatedAt":$t,"deleted":false}]""",
        )
        SyncEngine(db, fakeApi(cloud)).run(config)

        val accounts = accountRepo.all()
        println("MERGE_DEBUG=" + accounts.map { "${it.name}|uid=${it.uid}|bal=${it.balanceCents}|upd=${it.updatedAt}" })
        assertEquals("同名账户应合并，不应出现两个支付宝", 1, accounts.size)
        assertEquals(5_000L, accounts.first().balanceCents)
    }

    /** 分页：hasMore=true 时应收敛并继续拉取 */
    @Test
    fun paginationFollowsServerCursor() = runBlocking {
        val t = 1_700_000_000_000L
        var pullCount = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val body = when {
                path.endsWith("/sync/meta") ->
                    """{"serverTime":1,"lastUpdatedAt":$t,"transactionCount":2,"accountCount":0}"""
                path.endsWith("/sync/pull") -> {
                    pullCount++
                    val since = req.url.queryParameter("since")?.toLongOrNull() ?: 0
                    val sinceUid = req.url.queryParameter("sinceUid") ?: ""
                    when {
                        // 第一页：1 条（t-10），还有更多，游标指向 (t-10, tx1)
                        since < t - 10 ->
                            """{"serverTime":1,"lastUpdatedAt":$t,"accounts":[],"transactions":[{"uid":"tx1","amount":1.0,"type":"EXPENSE","note":"a","rawInput":"a","occurredAt":$t,"createdAt":$t,"updatedAt":${t - 10},"deleted":false}],"hasMore":true,"nextSince":${t - 10},"nextSinceUid":"tx1"}"""
                        // 第二页：按 (updatedAt, uid) 游标继续
                        since == t - 10 && sinceUid == "tx1" ->
                            """{"serverTime":1,"lastUpdatedAt":$t,"accounts":[],"transactions":[{"uid":"tx2","amount":2.0,"type":"EXPENSE","note":"b","rawInput":"b","occurredAt":$t,"createdAt":$t,"updatedAt":${t - 5},"deleted":false}],"hasMore":true,"nextSince":${t - 5},"nextSinceUid":"tx2"}"""
                        // 第三页：最后一条
                        else ->
                            """{"serverTime":1,"lastUpdatedAt":$t,"accounts":[],"transactions":[{"uid":"tx3","amount":3.0,"type":"EXPENSE","note":"c","rawInput":"c","occurredAt":$t,"createdAt":$t,"updatedAt":$t,"deleted":false}],"hasMore":false}"""
                    }
                }
                else -> "{}"
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        SyncEngine(db, SyncApi(http)).run(config)

        val txs = txRepo.observeRecent(10).first()
        assertEquals("分页应拉全三页", 3, txs.size)
        assertEquals(listOf("c", "b", "a"), txs.map { it.note })
        assertTrue("应请求多次 pull，实际 $pullCount", pullCount >= 3)
    }
}
