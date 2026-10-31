package com.ddl.eori.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    /** 首页最近账单（按发生时间倒序） */
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Update
    suspend fun update(tx: TransactionEntity)

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
        WHERE occurredAt >= :from AND occurredAt < :to
        """
    )
    fun observeTotals(from: Long, to: Long): Flow<PeriodTotals>

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
        WHERE occurredAt >= :from AND occurredAt < :to
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun observeDaily(from: Long, to: Long, tzOffsetMinutes: Int): Flow<List<DailySum>>
}
