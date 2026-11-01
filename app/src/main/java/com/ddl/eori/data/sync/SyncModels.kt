package com.ddl.eori.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 同步配置：Worker 地址 + 自设密钥。
 *
 * 安全约定：
 * - 只允许 https 协议，避免账单在公共网络明文传输（本机调试除外）；
 * - token 只走 `Authorization: Bearer` 头，不进 URL、不进日志。
 */
@Serializable
data class SyncConfig(
    val endpoint: String = "",
    val token: String = "",
) {
    val isReady: Boolean get() = endpoint.isNotBlank() && token.isNotBlank()

    /** 是否满足安全要求（https 或本地回环调试地址） */
    val isSecureEndpoint: Boolean
        get() {
            val e = endpoint.trim().lowercase()
            return e.startsWith("https://") ||
                e.startsWith("http://127.0.0.1") ||
                e.startsWith("http://localhost") ||
                e.startsWith("http://10.0.2.2")
        }

    companion object {
        /** 归一化：去掉尾部 /，缺协议时补 https，避免用户填一半 */
        fun normalize(endpoint: String): String {
            var e = endpoint.trim().trimEnd('/')
            if (e.isEmpty()) return e
            if (!e.startsWith("http://", true) && !e.startsWith("https://", true)) {
                e = "https://$e"
            }
            return e
        }
    }
}

// ---------------------------------------------------------------- 传输格式
// 与服务端 Worker 一一对应；字段用 Long/String 以避免 JS 数字精度问题。

@Serializable
data class SyncAccountDto(
    val uid: String,
    val name: String,
    val type: String,
    /**
     * 余额，单位：元，保留两位小数（如 1234.56）。
     * 本地仍以「分」存储以保证精度，仅在传输边界换算。
     */
    val balance: Double? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class SyncTransactionDto(
    val uid: String,
    /**
     * 金额，单位：元，保留两位小数（如 20.5）。
     * 本地仍以「分」存储，仅传输边界换算。
     */
    val amount: Double,
    val type: String,
    @SerialName("accountUid") val accountUid: String? = null,
    val accountName: String? = null,
    val note: String = "",
    val rawInput: String = "",
    val occurredAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** GET /sync/meta —— 云端最后更新时间 */
@Serializable
data class SyncMetaResponse(
    val serverTime: Long = 0,
    @SerialName("lastUpdatedAt") val lastUpdatedAt: Long = 0,
    @SerialName("transactionCount") val transactionCount: Int = 0,
    @SerialName("accountCount") val accountCount: Int = 0,
)

/** GET /sync/pull?since=xxx —— 云端比 since 新的记录 */
@Serializable
data class SyncPullResponse(
    val serverTime: Long = 0,
    @SerialName("lastUpdatedAt") val lastUpdatedAt: Long = 0,
    val accounts: List<SyncAccountDto> = emptyList(),
    val transactions: List<SyncTransactionDto> = emptyList(),
    /** 云端是否还有更早未拉取的数据（服务端计算，避免客户端猜分页边界） */
    val hasMore: Boolean = false,
    /** 下一页的 since 游标（服务端给出，保证不重不漏） */
    val nextSince: Long? = null,
    /** 与 nextSince 组成复合游标 (updatedAt, uid)，解决同毫秒记录分页 */
    val nextSinceUid: String? = null,
)

/** POST /sync/push —— 上传本地增量，服务端返回合并后的最新时间戳 */
@Serializable
data class SyncPushRequest(
    val accounts: List<SyncAccountDto> = emptyList(),
    val transactions: List<SyncTransactionDto> = emptyList(),
)

@Serializable
data class SyncPushResponse(
    val ok: Boolean = true,
    val accepted: Int = 0,
    val serverTime: Long = 0,
    @SerialName("lastUpdatedAt") val lastUpdatedAt: Long = 0,
)

/** 同步方向 */
enum class SyncDirection {
    /** 云端较新 -> 拉取到手机 */
    PULL,

    /** 本地较新 -> 上传到云端 */
    PUSH,

    /** 两端一致，无需传输 */
    UP_TO_DATE,
}

/** 一次同步的结果 */
data class SyncOutcome(
    val direction: SyncDirection,
    val accountsSynced: Int,
    val transactionsSynced: Int,
    val finishedAt: Long,
)

/** 同步失败原因，UI 直接展示 [message] */
data class SyncError(override val message: String) : Exception(message)

/** 同步状态（设置页展示） */
data class SyncStatus(
    val running: Boolean = false,
    /** 最近一次成功同步的时间，null 表示从未同步 */
    val lastSyncAt: Long? = null,
    /** 最近一次同步方向描述 */
    val lastSummary: String? = null,
    /** 最近一次失败信息 */
    val lastError: String? = null,
)
