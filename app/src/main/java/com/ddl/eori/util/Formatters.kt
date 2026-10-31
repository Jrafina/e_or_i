package com.ddl.eori.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 金额统一以“分”（Long）在内存与数据库流转，只在展示/输入边界做转换，
 * 避免 Double 的浮点误差（0.1 + 0.2 != 0.3）。
 */
object Money {

    /** "20.5" / "20" / "¥20.50" -> 2050 分；无法解析或 <=0 返回 null */
    fun parseToCents(input: String): Long? {
        val cleaned = input.trim()
            .replace("¥", "")
            .replace("￥", "")
            .replace(",", "")
            .replace("元", "")
            .trim()
        if (cleaned.isEmpty()) return null
        val value = cleaned.toBigDecimalOrNull() ?: return null
        if (value <= BigDecimal.ZERO) return null
        return runCatching {
            value.setScale(2, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .longValueExact()
        }.getOrNull()
    }

    /** 2050 -> "20.50"（不带货币符号，便于输入框编辑） */
    fun formatPlain(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val yuan = abs / 100
        val fen = abs % 100
        val body = if (fen == 0L) yuan.toString() else "$yuan.${fen.toString().padStart(2, '0')}"
        return if (negative) "-$body" else body
    }

    /** 2050 -> "¥20.50"（账单/统计展示，固定两位小数，保证数字对齐） */
    fun format(cents: Long): String = "¥${formatFixed2(cents)}"

    /** 带正负号：收入 +¥0.30 / 支出 -¥20.00 */
    fun formatSigned(cents: Long, income: Boolean): String {
        val sign = if (income) "+" else "-"
        return "$sign¥${formatFixed2(if (cents < 0) -cents else cents)}"
    }

    /** 固定两位小数，不带货币符号：2050 -> "20.50" */
    fun formatFixed2(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val body = "${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
        return if (negative) "-$body" else body
    }
}

/**
 * 日期工具：统一使用设备本地时区。
 * 统计区间采用 [月初 00:00, 下月初 00:00) 的半开区间。
 */
object Dates {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun startOfDay(timeMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /** 指定年月（1-based month）的 [start, end) 区间 */
    fun monthRange(year: Int, month: Int, zone: TimeZone = TimeZone.getDefault()): LongRange {
        val start = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, 1, 0, 0, 0)
        }.timeInMillis
        val end = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, 1, 0, 0, 0)
            add(Calendar.MONTH, 1)
        }.timeInMillis
        return start until end
    }

    /** 本月已过天数（含今天），至少为 1 */
    fun elapsedDaysInMonth(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
        return cal.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
    }

    fun currentYearMonth(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Pair<Int, Int> {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
        return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH) + 1
    }

    /** 本月天数（用于趋势图 X 轴） */
    fun daysInMonth(year: Int, month: Int, zone: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, 1)
        }
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    fun formatDateTime(timeMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).apply { this.timeZone = zone }
        return fmt.format(Date(timeMillis))
    }

    fun formatDayLabel(timeMillis: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
        val target = startOfDay(timeMillis, zone)
        val today = startOfDay(now, zone)
        return when {
            target == today -> "今天"
            target == today - DAY_MS -> "昨天"
            else -> {
                val fmt = SimpleDateFormat("MM-dd", Locale.CHINA).apply { this.timeZone = zone }
                fmt.format(Date(timeMillis))
            }
        }
    }

    fun formatMonthLabel(year: Int, month: Int): String = "${year}年${month}月"
}
