package com.ddl.eori.domain

import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.local.TxType

/**
 * AI 解析结果（已通过校验，可直接落库）。
 * 解析失败时不会产生本对象，而是走失败分支降级手动表单。
 */
data class ParsedTransaction(
    val amountCents: Long,
    val type: TxType,
    val accountId: Long?,
    val accountName: String?,
    val note: String,
    val occurredAt: Long,
)

/**
 * 解析失败原因分类，用于给用户明确提示（并决定是否降级手动表单）。
 */
sealed interface ParseError {
    /** 未配置 Base URL / Key / 模型 */
    data object NotConfigured : ParseError

    /** 网络异常、超时 */
    data class Network(val detail: String) : ParseError

    /** HTTP 非 2xx（含鉴权失败） */
    data class Http(val code: Int, val detail: String) : ParseError

    /** 返回内容不是合法 JSON，或字段不满足约束 */
    data class InvalidContent(val detail: String) : ParseError

    /** 金额无法识别 / 类型非法 */
    data object Unparsable : ParseError

    /** 用户取消 */
    data object Cancelled : ParseError

    fun toMessage(): String = when (this) {
        NotConfigured -> "未配置 AI，已切换手动记账"
        is Network -> "网络异常：$detail，已切换手动记账"
        is Http -> when {
            code == 401 || code == 403 -> "鉴权失败（$code），请检查 API Key"
            code == 404 -> "接口不存在（404），请检查 Base URL"
            code == 429 -> "请求过于频繁（429），请稍后再试"
            else -> "请求失败（$code）"
        }
        is InvalidContent -> "AI 返回内容无法解析，已切换手动记账"
        Unparsable -> "未能识别金额，已切换手动记账"
        Cancelled -> "已取消"
    }
}

/** 账户类型的展示名与默认排序 */
fun AccountType.label(): String = when (this) {
    AccountType.BANK -> "银行卡"
    AccountType.ALIPAY -> "支付宝"
    AccountType.WECHAT -> "微信"
    AccountType.CASH -> "现金"
    AccountType.OTHER -> "其他"
}

/** 账户类型的默认账户名（用于自动匹配/兜底展示） */
fun AccountType.defaultName(): String = when (this) {
    AccountType.BANK -> "银行卡"
    AccountType.ALIPAY -> "支付宝"
    AccountType.WECHAT -> "微信"
    AccountType.CASH -> "现金"
    AccountType.OTHER -> "其他"
}

/** AI 返回的账户名 -> 账户类型（仅用于展示提示，不用于创建账户） */
fun guessAccountType(rawName: String?): AccountType {
    val n = rawName.orEmpty()
    return when {
        n.contains("支付宝") || n.contains("花呗") || n.contains("余额宝") -> AccountType.ALIPAY
        n.contains("微信") || n.contains("零钱") -> AccountType.WECHAT
        n.contains("现金") -> AccountType.CASH
        n.contains("银行") || n.contains("卡") -> AccountType.BANK
        else -> AccountType.OTHER
    }
}
