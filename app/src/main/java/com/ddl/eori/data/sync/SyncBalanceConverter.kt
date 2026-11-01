package com.ddl.eori.data.sync

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 本地「分」<-> 云端「元」换算。
 *
 * - 本地数据库以分（Long）存储，保证累加无误差；
 * - D1 的 accounts.balance 按需求以「元」为单位、保留两位小数；
 * - 换算全部使用 BigDecimal，避免 double 除法产生 10.990000000000002 这类脏数据。
 */
class SyncBalanceConverter {

    /** 分 -> 元（两位小数） */
    fun centsToYuan(cents: Long): Double =
        BigDecimal(cents)
            .divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            .toDouble()

    /** 元 -> 分（四舍五入到两位小数） */
    fun yuanToCents(yuan: Double): Long {
        if (yuan.isNaN() || yuan.isInfinite()) return 0L
        return BigDecimal(yuan.toString())
            .multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }
}
