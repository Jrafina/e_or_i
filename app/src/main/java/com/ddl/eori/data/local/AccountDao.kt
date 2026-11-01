package com.ddl.eori.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE uid = :uid LIMIT 1")
    suspend fun getByUid(uid: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE uid IN (:uids)")
    suspend fun getByUids(uids: List<String>): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE updatedAt > :since ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun getSince(since: Long, limit: Int): List<AccountEntity>

    @Query("SELECT IFNULL(MAX(updatedAt), 0) FROM accounts")
    suspend fun lastUpdatedAt(): Long

    /**
     * 有效账户数：余额为 null 且没有任何账单的账户视为“启动预置的空壳账户”，
     * 不算用户真实数据。重装 App 后本地只有这些空壳，必须判定为“本地为空”。
     */
    @Query(
        """
        SELECT COUNT(*) FROM accounts a
        WHERE a.balanceCents IS NOT NULL
           OR EXISTS (SELECT 1 FROM transactions t WHERE t.accountId = a.id AND t.deleted = 0)
        """
    )
    suspend fun meaningfulCount(): Int

    @Insert
    suspend fun insertAll(items: List<AccountEntity>): List<Long>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY createdAt ASC")
    suspend fun getAll(): List<AccountEntity>

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 原子调整账户余额（记账/改账/删账时调用）。
     * 余额为 null（用户未填写）时按 0 起算，保证“记完账余额会变”的直觉行为。
     *
     * 必须同时刷新 updatedAt：同步是按 updatedAt 做增量的，
     * 否则余额变了但时间戳没变，云端就收不到新余额（表现为 balance 一直为 NULL）。
     */
    @Query(
        "UPDATE accounts SET balanceCents = IFNULL(balanceCents, 0) + :deltaCents, updatedAt = :now " +
            "WHERE id = :accountId"
    )
    suspend fun adjustBalance(accountId: Long, deltaCents: Long, now: Long)

    /** 直接设置余额（同步补算用），同时刷新 updatedAt */
    @Query("UPDATE accounts SET balanceCents = :balanceCents, updatedAt = :now WHERE id = :accountId")
    suspend fun setBalance(accountId: Long, balanceCents: Long?, now: Long)

    /** 账户列表 + 账单数量（不级联删除账单） */
    @Query(
        """
        SELECT a.id AS id, a.name AS name, a.type AS type, a.balanceCents AS balanceCents,
               (SELECT COUNT(*) FROM transactions t WHERE t.accountId = a.id) AS txCount
        FROM accounts a
        ORDER BY a.createdAt ASC
        """
    )
    fun observeWithCounts(): Flow<List<AccountWithCount>>
}
