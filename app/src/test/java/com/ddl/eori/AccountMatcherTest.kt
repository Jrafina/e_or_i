package com.ddl.eori

import com.ddl.eori.data.local.AccountEntity
import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.sync.SyncConfig
import com.ddl.eori.domain.AccountMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账户匹配测试：对应“说卡2却记到卡1”的线上问题。
 */
class AccountMatcherTest {

    private fun account(id: Long, name: String, type: AccountType) =
        AccountEntity(id = id, name = name, type = type, balanceCents = null, createdAt = id, uid = "u$id")

    private val twoCards = listOf(
        account(1, "卡1", AccountType.BANK),
        account(2, "卡2", AccountType.BANK),
        account(3, "支付宝", AccountType.ALIPAY),
        account(4, "微信", AccountType.WECHAT),
    )

    @Test
    fun matchesSecondCardExactly() {
        val hit = AccountMatcher.match(twoCards, "卡2", "卡2消费2元")
        assertEquals("卡2", hit?.name)
    }

    @Test
    fun matchesChineseNumeralCardName() {
        val cards = listOf(
            account(1, "卡1", AccountType.BANK),
            account(2, "卡2", AccountType.BANK),
        )
        // AI 原样返回“卡二”，原话也是“卡二”
        assertEquals("卡2", AccountMatcher.match(cards, "卡二", "用卡二买了咖啡 15 元")?.name)
        // 账户名本身写成「卡二」时也要命中
        val cnNamed = listOf(account(1, "卡一", AccountType.BANK), account(2, "卡二", AccountType.BANK))
        assertEquals("卡二", AccountMatcher.match(cnNamed, "卡二", "卡二花了 3 元")?.name)
    }

    @Test
    fun doesNotConfuseCard1WithCard12() {
        val cards = listOf(
            account(1, "卡1", AccountType.BANK),
            account(2, "卡12", AccountType.BANK),
        )
        assertEquals("卡12", AccountMatcher.match(cards, "卡12", "卡12消费 9 元")?.name)
    }

    @Test
    fun aiReturningGenericBankMatchedFromRawInput() {
        // 模型常见错误：只说“银行卡”，但原话里明确写了卡2
        val hit = AccountMatcher.match(twoCards, "银行卡", "卡2消费2元")
        assertEquals("卡2", hit?.name)
    }

    @Test
    fun matchesAlipayAndWechat() {
        assertEquals("支付宝", AccountMatcher.match(twoCards, "支付宝", "吃饭花了支付宝20元")?.name)
        assertEquals("微信", AccountMatcher.match(twoCards, "微信", "看多多视频微信收入0.3")?.name)
    }

    @Test
    fun ambiguousGenericBankDoesNotGuess() {
        // 原话没有指明哪张卡、AI 也只说“银行卡” -> 不应乱猜
        assertNull(AccountMatcher.match(twoCards, "银行卡", "花了 20 元"))
    }

    @Test
    fun singleBankAccountCanBeGuessed() {
        val oneCard = listOf(
            account(1, "招行储蓄卡", AccountType.BANK),
            account(2, "支付宝", AccountType.ALIPAY),
        )
        assertEquals("招行储蓄卡", AccountMatcher.match(oneCard, "银行卡", "花了 20 元")?.name)
    }

    @Test
    fun bankShortNameMatches() {
        val cards = listOf(
            account(1, "招商银行卡", AccountType.BANK),
            account(2, "建设银行卡", AccountType.BANK),
        )
        assertEquals("建设银行卡", AccountMatcher.match(cards, "建行", "建行消费 30 元")?.name)
        assertEquals("招商银行卡", AccountMatcher.match(cards, "招行", "招行付了 12 元")?.name)
    }

    @Test
    fun emptyAccountsReturnsNull() {
        assertNull(AccountMatcher.match(emptyList(), "卡1", "卡1消费 1 元"))
    }

    @Test
    fun numberTagsExtraction() {
        assertTrue(AccountMatcher.numberTags("卡2消费2元").contains("2"))
        assertTrue(AccountMatcher.numberTags("用卡一买了水").contains("1"))
        assertTrue(AccountMatcher.numberTags("尾号 8888 的卡").contains("8888"))
    }
}

/**
 * 同步配置的安全约束测试。
 */
class SyncConfigTest {

    @Test
    fun httpsIsSecure() {
        assertTrue(SyncConfig(endpoint = "https://eori.abc.workers.dev", token = "x").isSecureEndpoint)
    }

    @Test
    fun plainHttpIsRejected() {
        assertFalse(SyncConfig(endpoint = "http://example.com", token = "x").isSecureEndpoint)
        assertFalse(SyncConfig(endpoint = "http://192.168.1.5:8787", token = "x").isSecureEndpoint)
    }

    @Test
    fun localhostAllowedForDebugging() {
        assertTrue(SyncConfig(endpoint = "http://localhost:8787", token = "x").isSecureEndpoint)
        assertTrue(SyncConfig(endpoint = "http://127.0.0.1:8787", token = "x").isSecureEndpoint)
        assertTrue(SyncConfig(endpoint = "http://10.0.2.2:8787", token = "x").isSecureEndpoint)
    }

    @Test
    fun normalizesEndpoint() {
        assertEquals("https://a.workers.dev", SyncConfig.normalize("a.workers.dev"))
        assertEquals("https://a.workers.dev", SyncConfig.normalize("https://a.workers.dev/"))
        assertEquals("https://a.workers.dev", SyncConfig.normalize("  https://a.workers.dev//  "))
        assertEquals("", SyncConfig.normalize("   "))
    }

    @Test
    fun readinessRequiresBothFields() {
        assertFalse(SyncConfig("", "t").isReady)
        assertFalse(SyncConfig("https://a", "").isReady)
        assertTrue(SyncConfig("https://a", "t").isReady)
    }
}
