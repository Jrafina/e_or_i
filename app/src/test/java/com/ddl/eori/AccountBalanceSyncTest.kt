package com.ddl.eori

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.local.EoriDatabase
import com.ddl.eori.data.local.TxType
import com.ddl.eori.data.repo.AccountRepository
import com.ddl.eori.data.repo.TransactionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 余额同步相关回归测试。
 *
 * 对应线上问题：
 * 1. D1 的 balance 一直是 NULL —— 根因是记账改了余额却没刷新 updatedAt，
 *    增量同步永远认为“账户没变”，于是余额从未被上传。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AccountBalanceSyncTest {

    private lateinit var db: EoriDatabase
    private lateinit var accountRepo: AccountRepository
    private lateinit var txRepo: TransactionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EoriDatabase::class.java,
        ).allowMainThreadQueries().build()
        accountRepo = AccountRepository(db.accountDao(), db.transactionDao())
        txRepo = TransactionRepository(db)
    }

    @After
    fun tearDown() = db.close()

    /**
     * 核心回归：记账后账户的 updatedAt 必须刷新，
     * 否则同步的增量筛选（updatedAt > since）会漏掉余额变化。
     */
    @Test
    fun balanceChangeBumpsUpdatedAt() = runBlocking {
        val id = accountRepo.create("卡1", AccountType.BANK, 100_000L)
        val before = accountRepo.get(id)!!
        val beforeUpdated = before.updatedAt

        // 等 2ms，确保时间戳能前进（毫秒精度）
        Thread.sleep(2)
        val acc = accountRepo.get(id)!!
        txRepo.add(2_000, TxType.EXPENSE, acc, "午饭", "午饭20元", System.currentTimeMillis())

        val after = accountRepo.get(id)!!
        assertEquals("余额应减少", 98_000L, after.balanceCents)
        assertTrue(
            "余额变化必须刷新 updatedAt，否则同步漏传（before=$beforeUpdated, after=${after.updatedAt}）",
            after.updatedAt > beforeUpdated,
        )
    }

    @Test
    fun deletedTransactionAlsoBumpsUpdatedAt() = runBlocking {
        val id = accountRepo.create("卡2", AccountType.BANK, 50_000L)
        val acc = accountRepo.get(id)!!
        val txId = txRepo.add(1_000, TxType.EXPENSE, acc, "咖啡", "咖啡10元", System.currentTimeMillis())

        val beforeDelete = accountRepo.get(id)!!.updatedAt
        Thread.sleep(2)
        txRepo.delete(txId)

        val afterDelete = accountRepo.get(id)!!
        assertEquals("删除账单应退回余额", 50_000L, afterDelete.balanceCents)
        assertTrue("删除也要刷新 updatedAt", afterDelete.updatedAt > beforeDelete)
    }

    /** 记账后余额一定不为 null，同步上传时才能是真实数字而不是 NULL */
    @Test
    fun balanceIsNotNullAfterAnyTransaction() = runBlocking {
        // 建账户时不填余额（null）
        val id = accountRepo.create("无初始余额卡", AccountType.BANK, null)
        assertEquals("初始应为 null", null, accountRepo.get(id)!!.balanceCents)

        val acc = accountRepo.get(id)!!
        txRepo.add(300, TxType.INCOME, acc, "红包", "收红包0.3", System.currentTimeMillis())

        val balance = accountRepo.get(id)!!.balanceCents
        assertNotNull("记账后余额不应再是 null", balance)
        assertEquals(300L, balance)
    }

    @Test
    fun netComputationMatchesBalanceChange() = runBlocking {
        val id = accountRepo.create("测试卡", AccountType.BANK, 0L)
        val acc = accountRepo.get(id)!!
        txRepo.add(5_000, TxType.EXPENSE, acc, "a", "a", System.currentTimeMillis())
        txRepo.add(1_200, TxType.INCOME, acc, "b", "b", System.currentTimeMillis())

        val totals = txRepo.observeTotalsByAccount(id).first()
        val net = totals.incomeCents - totals.expenseCents
        assertEquals("余额应等于账单净额", net, accountRepo.get(id)!!.balanceCents)
        assertEquals(-3_800L, net)
    }
}
