package com.ddl.eori.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 账单 DAO。
 *
 * 同步相关约定：`deleted` 为软删除标记，**所有面向 UI 的查询都过滤 `deleted = 0`**，
 * 只有同步逻辑（[getSince]、[getAllForSync]、[upsertFromSync]）才会接触到已删除记录。
 */
@Dao
interface TransactionDao {

    /** 首页最近账单（按发生时间倒序） */
    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    /** 某个账户下的账单（账户详情页） */
    @Query(
        "SELECT * FROM transactions WHERE accountId = :accountId AND deleted = 0 " +
            "ORDER BY occurredAt DESC, id DESC LIMIT :limit"
    )
    fun observeByAccount(accountId: Long, limit: Int): Flow<List<TransactionEntity>>

    /** 某账户的收入/支出汇总 */
    @Query(
        """
        SELECT
          IFNULL(SUM(CASE WHEN type = 'INCOME' THEN amountCents ELSE 0 END), 0) AS incomeCents,
          IFNULL(SUM(CASE WHEN type = 'EXPENSE' THEN amountCents ELSE 0 END), 0) AS expenseCents
        FROM transactions WHERE accountId = :accountId AND deleted = 0
        """
    )
    fun observeTotalsByAccount(accountId: Long): Flow<PeriodTotals>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE uid = :uid LIMIT 1")
    suspend fun getByUid(uid: String): TransactionEntity?

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Update
    suspend fun update(tx: TransactionEntity)

    /** 只标记删除（保留记录以便同步到另一端） */
    @Query("UPDATE transactions SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: Long, updatedAt: Long)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 统计区间汇总。传入 [from, to) 的毫秒区间。
     * 空数据时 SUM 返回 null，用 IFNULL 兜底为 0。
     */
    @Query(
        """
        SELECT
          IFNULL(SUM(CASE WHEN type = 'INCOME' THEN amountCents ELSE 0 END), 0) AS incomeCents,
          IFNULL(SUM(CASE WHEN type = 'EXPENSE' THEN amountCents ELSE 0 END), 0) AS expenseCents
        FROM transactions
        WHERE occurredAt >= :from AND occurredAt < :to AND deleted = 0
        """
    )
    fun observeTotals(from: Long, to: Long): Flow<PeriodTotals>

    /** 区间内金额最大的 N 笔支出（本月消费 Top） */
    @Query(
        """
        SELECT * FROM transactions
        WHERE type = 'EXPENSE' AND occurredAt >= :from AND occurredAt < :to AND deleted = 0
        ORDER BY amountCents DESC, occurredAt DESC
        LIMIT :limit
        """
    )
    fun observeTopExpenses(from: Long, to: Long, limit: Int): Flow<List<TransactionEntity>>

    /**
     * 按日聚合（SQLite 的 strftime 返回 UTC，这里按本地时区偏移校正）。
     * [tzOffsetMinutes] 为本地时区相对 UTC 的分钟偏移，例如东八区传 480。
     */
    @Query(
        """
        SELECT strftime('%Y-%m-%d', (occurredAt + :tzOffsetMinutes * 60000) / 1000, 'unixepoch') AS day,
               IFNULL(SUM(CASE WHEN type = 'INCOME' THEN amountCents ELSE 0 END), 0) AS incomeCents,
               IFNULL(SUM(CASE WHEN type = 'EXPENSE' THEN amountCents ELSE 0 END), 0) AS expenseCents
        FROM transactions
        WHERE occurredAt >= :from AND occurredAt < :to AND deleted = 0
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun observeDaily(from: Long, to: Long, tzOffsetMinutes: Int): Flow<List<DailySum>>

    // ---------------------------------------------------------------- 同步用

    /** 比给定时间戳更新的记录（含删除标记），用于增量上传 */
    @Query("SELECT * FROM transactions WHERE updatedAt > :since ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun getSince(since: Long, limit: Int): List<TransactionEntity>

    /** 全量（首次同步 / 拉取用） */
    @Query("SELECT * FROM transactions ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun getAllForSync(limit: Int): List<TransactionEntity>

    /** 本地最后修改时间（同步方向判断依据） */
    @Query("SELECT IFNULL(MAX(updatedAt), 0) FROM transactions")
    suspend fun lastUpdatedAt(): Long

    /** 有效账单数（不含已删除），用于判断“本地是否是全新安装” */
    @Query("SELECT COUNT(*) FROM transactions WHERE deleted = 0")
    suspend fun visibleCount(): Int

    /** 从云端写入（批量插入，调用方需先按 uid 去重） */
    @Insert
    suspend fun insertAll(items: List<TransactionEntity>)

    /** 服务端版本覆盖本地：按 uid 对齐，返回被覆盖的本地 id 以便复用 */
    @Query("SELECT * FROM transactions WHERE uid IN (:uids)")
    suspend fun getByUids(uids: List<String>): List<TransactionEntity>
}
