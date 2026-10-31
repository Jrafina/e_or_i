package com.ddl.eori.data.repo

import androidx.room.withTransaction
import com.ddl.eori.data.local.AccountDao
import com.ddl.eori.data.local.AccountEntity
import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.local.AccountWithCount
import com.ddl.eori.data.local.EoriDatabase
import com.ddl.eori.data.local.PeriodTotals
import com.ddl.eori.data.local.TransactionDao
import com.ddl.eori.data.local.TransactionEntity
import com.ddl.eori.data.local.TxType
import kotlinx.coroutines.flow.Flow

/**
 * 账户仓库。
 *
 * 关键约定：删除账户**不级联删除账单**。账单里的 accountName 快照保留，
 * UI 通过 accountId 是否还能在账户表命中来判断是否显示“已删除账户”。
 */
class AccountRepository(
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
) {
    fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()

    fun observeAccountsWithCount(): Flow<List<AccountWithCount>> = accountDao.observeWithCounts()

    suspend fun get(id: Long): AccountEntity? = accountDao.getById(id)

    suspend fun all(): List<AccountEntity> = accountDao.getAll()

    suspend fun create(name: String, type: AccountType, balanceCents: Long?): Long {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "账户名不能为空" }
        return accountDao.insert(
            AccountEntity(name = cleanName, type = type, balanceCents = balanceCents)
        )
    }

    suspend fun update(account: AccountEntity) {
        require(account.name.isNotBlank()) { "账户名不能为空" }
        accountDao.update(account.copy(name = account.name.trim()))
    }

    /** 删除账户，账单保留（accountId 变为悬空引用） */
    suspend fun delete(id: Long) {
        accountDao.deleteById(id)
    }

    /**
     * 默认账户：优先返回与类型匹配的账户，其次返回最早创建的账户。
     * 用于 AI 解析后自动匹配账户。
     */
    suspend fun findByTypeOrFirst(type: AccountType): AccountEntity? {
        val accounts = accountDao.getAll()
        if (accounts.isEmpty()) return null
        return accounts.firstOrNull { it.type == type } ?: accounts.first()
    }

    /** 首页无账户时自动创建 4 个常用账户，降低首次使用门槛 */
    suspend fun ensureDefaults() {
        if (accountDao.getAll().isNotEmpty()) return
        listOf(
            "支付宝" to AccountType.ALIPAY,
            "微信" to AccountType.WECHAT,
            "银行卡" to AccountType.BANK,
            "现金" to AccountType.CASH,
        ).forEach { (name, type) ->
            accountDao.insert(AccountEntity(name = name, type = type))
        }
    }
}

/**
 * 账单仓库。
 *
 * 记账的核心约定：**账单与账户余额在同一个数据库事务内变更**，
 * 支出减少余额、收入增加余额；编辑账单先撤销旧影响再应用新影响；
 * 删除账单则把余额加回去。这样账户余额始终等于“初始余额 + 所有账单的净额”。
 */
class TransactionRepository(
    private val db: EoriDatabase,
) {
    private val transactionDao: TransactionDao = db.transactionDao()
    private val accountDao: AccountDao = db.accountDao()

    fun observeRecent(limit: Int = 50): Flow<List<TransactionEntity>> = transactionDao.observeRecent(limit)

    fun observeTotals(from: Long, to: Long): Flow<PeriodTotals> =
        transactionDao.observeTotals(from, to)

    fun observeDaily(from: Long, to: Long, tzOffsetMinutes: Int) =
        transactionDao.observeDaily(from, to, tzOffsetMinutes)

    suspend fun get(id: Long): TransactionEntity? = transactionDao.getById(id)

    suspend fun add(
        amountCents: Long,
        type: TxType,
        account: AccountEntity?,
        note: String,
        rawInput: String,
        occurredAt: Long,
    ): Long {
        require(amountCents > 0) { "金额必须大于 0" }
        return db.withTransaction {
            val id = transactionDao.insert(
                TransactionEntity(
                    amountCents = amountCents,
                    type = type,
                    accountId = account?.id,
                    accountName = account?.name,
                    note = note.trim(),
                    rawInput = rawInput.trim(),
                    occurredAt = occurredAt,
                )
            )
            if (account != null) {
                accountDao.adjustBalance(account.id, signedDelta(amountCents, type))
            }
            id
        }
    }

    /** 编辑账单：先撤销旧账单对余额的影响，再应用新值（含换账户） */
    suspend fun update(tx: TransactionEntity) {
        require(tx.amountCents > 0) { "金额必须大于 0" }
        db.withTransaction {
            val old = transactionDao.getById(tx.id) ?: return@withTransaction
            old.accountId?.let { accountDao.adjustBalance(it, -signedDelta(old.amountCents, old.type)) }
            transactionDao.update(tx)
            tx.accountId?.let { accountDao.adjustBalance(it, signedDelta(tx.amountCents, tx.type)) }
        }
    }

    /** 删除账单：把金额还回账户 */
    suspend fun delete(id: Long) {
        db.withTransaction {
            val old = transactionDao.getById(id) ?: return@withTransaction
            old.accountId?.let { accountDao.adjustBalance(it, -signedDelta(old.amountCents, old.type)) }
            transactionDao.deleteById(id)
        }
    }

    /** 收入为正、支出为负 */
    private fun signedDelta(amountCents: Long, type: TxType): Long =
        if (type == TxType.INCOME) amountCents else -amountCents
}
