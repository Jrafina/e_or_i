package com.ddl.eori

import com.ddl.eori.data.sync.SyncAccountDto
import com.ddl.eori.data.sync.SyncBalanceConverter
import com.ddl.eori.data.sync.SyncTransactionDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * 云同步传输格式测试。
 *
 * 约定：D1 的 accounts.balance 字段单位为「元」，保留两位小数；
 * 本地仍以「分」存储（Long），只在传输边界换算，保证不丢精度。
 */
class SyncBalanceFormatTest {

    /** 只测换算逻辑，不启动 Robolectric/数据库 */
    private val converter = SyncBalanceConverter()

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun centsToYuanKeepsTwoDecimals() {
        assertEquals(0.3, converter.centsToYuan(30), 0.0)
        assertEquals(10.99, converter.centsToYuan(1099), 0.0)
        assertEquals(1234.56, converter.centsToYuan(123456), 0.0)
        assertEquals(-20.0, converter.centsToYuan(-2000), 0.0)
        assertEquals(0.0, converter.centsToYuan(0), 0.0)
    }

    @Test
    fun yuanToCentsRoundsHalfUp() {
        assertEquals(30L, converter.yuanToCents(0.3))
        assertEquals(1099L, converter.yuanToCents(10.99))
        assertEquals(123456L, converter.yuanToCents(1234.56))
        assertEquals(-2000L, converter.yuanToCents(-20.0))
        // 两位以上小数按四舍五入处理
        assertEquals(1001L, converter.yuanToCents(10.005))
    }

    @Test
    fun roundTripIsStable() {
        // 分 -> 元 -> 分 必须原样还原，不允许出现 10.990000000000002 这类误差
        listOf(1L, 30L, 99L, 1099L, 123456L, 99_999_999L, -2500L).forEach { cents ->
            assertEquals("往返失败: $cents", cents, converter.yuanToCents(converter.centsToYuan(cents)))
        }
    }

    @Test
    fun accountDtoUsesYuanFieldNamedBalance() {
        val dto = SyncAccountDto(
            uid = "u1",
            name = "卡1",
            type = "BANK",
            balance = 1234.56,
            createdAt = 1L,
            updatedAt = 2L,
        )
        val encoded = json.encodeToString(SyncAccountDto.serializer(), dto)
        // 字段名必须是 balance（不是 balanceCents）
        assert(encoded.contains("\"balance\"")) { "期望包含 balance 字段，实际: $encoded" }
        assert(!encoded.contains("balanceCents")) { "不应再出现 balanceCents，实际: $encoded" }
        // 值以元为单位
        assert(BigDecimal("1234.56").compareTo(BigDecimal(json.parseToJsonElement(encoded)
            .let { (it as kotlinx.serialization.json.JsonObject)["balance"]!!.toString() })) == 0)

        val decoded = json.decodeFromString(SyncAccountDto.serializer(), encoded)
        assertEquals(1234.56, decoded.balance ?: 0.0, 0.0)
    }

    @Test
    fun nullBalanceSerializesAsNull() {
        val dto = SyncAccountDto(uid = "u2", name = "现金", type = "CASH", balance = null, createdAt = 0, updatedAt = 0)
        val decoded = json.decodeFromString(SyncAccountDto.serializer(), json.encodeToString(SyncAccountDto.serializer(), dto))
        assertNull(decoded.balance)
    }

    @Test
    fun transactionAmountUsesYuanFieldNamedAmount() {
        // 账单金额同样改为「元」，字段名 amount
        val dto = SyncTransactionDto(
            uid = "t1",
            amount = 0.3,
            type = "INCOME",
            occurredAt = 1L,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val encoded = json.encodeToString(SyncTransactionDto.serializer(), dto)
        assert(encoded.contains("\"amount\":0.3")) { "账单金额应为元：$encoded" }
        assert(!encoded.contains("amountCents")) { "不应再出现 amountCents：$encoded" }
    }

    @Test
    fun amountRoundTripThroughYuan() {
        // 分 -> 元 -> 分 各种金额都要还原
        listOf(1L, 30L, 99L, 2050L, 123456L).forEach { cents ->
            val yuan = converter.centsToYuan(cents)
            assertEquals("金额 $cents 分往返失败", cents, converter.yuanToCents(yuan))
        }
    }
}
