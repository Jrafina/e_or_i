package com.ddl.eori

import com.ddl.eori.data.remote.ApiUrls
import com.ddl.eori.data.remote.JsonExtract
import com.ddl.eori.data.remote.ModelListParser
import com.ddl.eori.util.Dates
import com.ddl.eori.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MoneyTest {

    @Test
    fun parsesPlainAmount() {
        assertEquals(2000L, Money.parseToCents("20"))
        assertEquals(2050L, Money.parseToCents("20.5"))
        assertEquals(2050L, Money.parseToCents("20.50"))
    }

    @Test
    fun stripsCurrencySymbolsAndUnits() {
        assertEquals(2000L, Money.parseToCents("¥20"))
        assertEquals(2000L, Money.parseToCents("￥20元"))
        assertEquals(123456L, Money.parseToCents("1,234.56"))
    }

    @Test
    fun rejectsInvalidAmounts() {
        assertNull(Money.parseToCents(""))
        assertNull(Money.parseToCents("abc"))
        assertNull(Money.parseToCents("0"))
        assertNull(Money.parseToCents("-5"))
    }

    @Test
    fun roundsHalfUpToTwoDecimals() {
        assertEquals(1999L, Money.parseToCents("19.99"))
        assertEquals(100L, Money.parseToCents("1.004"))
    }

    @Test
    fun formatsCents() {
        assertEquals("20", Money.formatPlain(2000))
        assertEquals("20.05", Money.formatPlain(2005))
        assertEquals("¥0.30", Money.format(30))
        assertEquals("+¥0.30", Money.formatSigned(30, income = true))
        assertEquals("-¥20.00", Money.formatSigned(2000, income = false))
    }
}

class ApiUrlsTest {

    @Test
    fun appendsV1WhenMissing() {
        assertEquals(
            "https://api.openai.com/v1/models",
            ApiUrls.modelsUrl("https://api.openai.com"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ApiUrls.chatCompletionsUrl("https://api.openai.com"),
        )
    }

    @Test
    fun doesNotDuplicateVersionSegment() {
        assertEquals(
            "https://api.openai.com/v1/models",
            ApiUrls.modelsUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            ApiUrls.modelsUrl("https://api.openai.com/v1/"),
        )
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
            ApiUrls.modelsUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
    }

    @Test
    fun addsHttpsSchemeWhenMissing() {
        assertEquals("https://api.deepseek.com/v1/models", ApiUrls.modelsUrl("api.deepseek.com"))
    }

    @Test
    fun emptyBaseUrlYieldsEmptyUrl() {
        assertEquals("", ApiUrls.modelsUrl("   "))
    }
}

class JsonExtractTest {

    @Test
    fun extractsBareJson() {
        val obj = JsonExtract.extractObject("""{"amount": 20, "type": "expense"}""")
        assertEquals("20", JsonExtract.stringOf(obj!!, "amount"))
    }

    @Test
    fun extractsFromMarkdownFence() {
        val raw = """
            好的，解析如下：
            ```json
            {"amount": "0.3", "type": "income", "account": "微信"}
            ```
        """.trimIndent()
        val obj = JsonExtract.extractObject(raw)!!
        assertEquals("0.3", JsonExtract.stringOf(obj, "amount"))
        assertEquals("income", JsonExtract.stringOf(obj, "type"))
    }

    @Test
    fun extractsFromSurroundingText() {
        val raw = "结果：{\"amount\":20,\"type\":\"expense\"} 希望有帮助"
        val obj = JsonExtract.extractObject(raw)!!
        assertEquals("expense", JsonExtract.stringOf(obj, "type"))
    }

    @Test
    fun returnsNullForNonJson() {
        assertNull(JsonExtract.extractObject("抱歉，我无法解析"))
    }

    @Test
    fun handlesAccountObject() {
        val obj = JsonExtract.extractObject("""{"amount":1,"account":{"name":"支付宝"}}""")!!
        assertEquals("支付宝", JsonExtract.objectOrString(obj, "account"))
    }

    @Test
    fun matchesSnakeCaseAndCaseInsensitiveKeys() {
        val obj = JsonExtract.extractObject("""{"Occurred_At":"2025-01-01T10:00:00","Amount":3}""")!!
        assertEquals("3", JsonExtract.stringOf(obj, "amount"))
        assertEquals("2025-01-01T10:00:00", JsonExtract.stringOf(obj, "occurredAt"))
    }
}

class ModelListParserTest {

    @Test
    fun parsesOpenAiStyleList() {
        val body = """{"object":"list","data":[{"id":"gpt-4o-mini"},{"id":"gpt-4o"}]}"""
        assertEquals(listOf("gpt-4o-mini", "gpt-4o"), ModelListParser.parse(body))
    }

    @Test
    fun parsesModelsKeyFallback() {
        val body = """{"models":[{"id":"qwen-turbo"}]}"""
        assertEquals(listOf("qwen-turbo"), ModelListParser.parse(body))
    }

    @Test
    fun returnsEmptyOnGarbage() {
        assertTrue(ModelListParser.parse("<html>404</html>").isEmpty())
        assertTrue(ModelListParser.parse("").isEmpty())
    }
}

class DatesTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun monthRangeCoversWholeMonth() {
        val range = Dates.monthRange(2025, 2, zone)
        // 2025-02 有 28 天
        val days = ((range.last + 1 - range.first) / (24 * 60 * 60 * 1000L)).toInt()
        assertEquals(28, days)
    }

    @Test
    fun daysInMonthHandlesLeapYear() {
        assertEquals(29, Dates.daysInMonth(2024, 2, zone))
        assertEquals(28, Dates.daysInMonth(2025, 2, zone))
        assertEquals(31, Dates.daysInMonth(2025, 1, zone))
        assertEquals(30, Dates.daysInMonth(2025, 4, zone))
    }

    @Test
    fun elapsedDaysAtLeastOne() {
        val cal = java.util.Calendar.getInstance(zone).apply {
            clear()
            set(2025, 0, 1, 0, 0, 0)
        }
        assertTrue(Dates.elapsedDaysInMonth(cal.timeInMillis, zone) >= 1)
    }
}
