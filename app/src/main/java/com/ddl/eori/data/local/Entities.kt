package com.ddl.eori.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 账单类型。数据库中以字符串存储，便于后续扩展（如 transfer）。
 */
enum class TxType { EXPENSE, INCOME }

/**
 * 账户类型。
 */
enum class AccountType { BANK, ALIPAY, WECHAT, CASH, OTHER }

/**
 * 账户。
 *
 * 注意：账单对账户**不做外键级联删除**。删除账户后历史账单仍需保留，
 * 因此这里不使用 ForeignKey，账户名在删除后通过 accountName 快照兜底展示。
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "type") val type: AccountType,
    /** 可选余额，单位：分。null 表示用户未填写 */
    val balanceCents: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** 跨设备稳定标识 */
    val uid: String = "",
    /** 最后修改时间，用于增量同步 */
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 账单。
 *
 * - amountCents：金额统一以“分”为单位用 Long 存储，避免浮点误差。
 * - accountId 可为空（手动记账时用户可选择“不指定账户”）。
 * - accountName：保存账户名快照，账户被删除后仍能展示（显示为“已删除账户”由 UI 判断）。
 * - uid：跨设备同步的稳定标识（本地自增 id 无法跨设备对齐）。
 * - updatedAt：最后一次修改时间，增量同步以此判断“哪端更新”。
 * - deleted：软删除标记。同步需要把“删除了”这件事传到另一端，
 *   否则云端旧数据会在下次同步时把已删除的账单“复活”。UI 查询一律过滤 deleted = 0。
 */
@Entity(
    tableName = "transactions",
    indices = [Index("occurredAt"), Index("accountId")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 金额（分），恒 > 0 */
    val amountCents: Long,
    val type: TxType,
    val accountId: Long? = null,
    /** 账户名快照，账户删除后用于展示 */
    val accountName: String? = null,
    val note: String = "",
    /** 用户原始自然语言输入 */
    val rawInput: String = "",
    /** 发生时间（epoch millis） */
    val occurredAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    /** 跨设备稳定标识 */
    val uid: String = "",
    /** 最后修改时间（本地修改与云端合并都以此为准） */
    val updatedAt: Long = System.currentTimeMillis(),
    /** 软删除：true 表示已删除，仅用于把删除同步到另一端 */
    val deleted: Boolean = false,
)

/** 账户 + 其账单数量，用于账户列表展示 */
data class AccountWithCount(
    val id: Long,
    val name: String,
    val type: AccountType,
    val balanceCents: Long?,
    val txCount: Int,
)

/** 按日聚合结果（统计图用） */
data class DailySum(
    val day: String, // yyyy-MM-dd
    val incomeCents: Long,
    val expenseCents: Long,
)

/** 统计汇总 */
data class PeriodTotals(
    val incomeCents: Long = 0,
    val expenseCents: Long = 0,
) {
    val netCents: Long get() = incomeCents - expenseCents
}
