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
     */
    @Query("UPDATE accounts SET balanceCents = IFNULL(balanceCents, 0) + :deltaCents WHERE id = :accountId")
    suspend fun adjustBalance(accountId: Long, deltaCents: Long)

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
