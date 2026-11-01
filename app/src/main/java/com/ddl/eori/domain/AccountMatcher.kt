package com.ddl.eori.domain

import com.ddl.eori.data.local.AccountEntity
import com.ddl.eori.data.local.AccountType

/**
 * 把 AI 返回的账户名 / 用户原话，匹配到用户真实存在的账户。
 *
 * 之所以不能只靠 [com.ddl.eori.data.repo.AccountRepository.findByTypeOrFirst]：
 * 用户有「卡1」「卡2」两张银行卡时，按类型取第一个永远会命中卡1，
 * 这正是“说卡2却记到卡1”的根因。这里按优先级逐级匹配。
 */
object AccountMatcher {

    /** 中文数字 -> 阿拉伯数字，覆盖“卡一/卡二/第一张”这类说法 */
    private val cnDigits = mapOf(
        '零' to "0", '一' to "1", '二' to "2", '两' to "2", '三' to "3", '四' to "4",
        '五' to "5", '六' to "6", '七' to "7", '八' to "8", '九' to "9", '十' to "10",
    )

    /** 把“卡一”归一化成“卡1”，便于与账户名做数字比对 */
    fun normalize(text: String): String {
        val lower = text.lowercase().trim()
        val sb = StringBuilder()
        for (ch in lower) {
            val mapped = cnDigits[ch]
            if (mapped != null) sb.append(mapped) else sb.append(ch)
        }
        return sb.toString().replace(" ", "")
    }

    /**
     * 从一句话里抽出“卡N”中的数字标签，例如「卡2消费2元」-> ["2"]。
     * 用于在 AI 没识别对时兜底纠正。
     */
    fun numberTags(text: String): List<String> {
        val normalized = normalize(text)
        return Regex("""(?:卡|尾号|后四位|账户|账号|#)(\d{1,4})""").findAll(normalized)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    /**
     * 主匹配入口。
     *
     * @param aiAccount AI 返回的账户名（可能是 “卡2” / “银行卡” / “招行” / “其他”）
     * @param rawInput  用户原始输入，用于在 AI 结果不可靠时兜底
     */
    fun match(
        accounts: List<AccountEntity>,
        aiAccount: String?,
        rawInput: String,
    ): AccountEntity? {
        if (accounts.isEmpty()) return null
        val aiText = aiAccount.orEmpty().trim()

        // 1) 账户名完全相等（忽略大小写/空格/中文数字）
        exactMatch(accounts, aiText)?.let { return it }

        // 2) 原话里出现“卡N”等数字标签，且账户名里带同一数字 -> 精确到那张卡
        for (tag in numberTags(aiText) + numberTags(rawInput)) {
            val hit = accounts.firstOrNull { containsNumber(normalize(it.name), tag) }
            if (hit != null) return hit
        }

        // 3) 账户名包含关系（“招行” -> “招商银行卡” / “微信零钱” -> “微信”）
        containsMatch(accounts, aiText)?.let { return it }
        containsMatch(accounts, rawInput)?.let { return it }

        // 4) 银行名匹配：原话里出现某个账户名的前两个字（如“招行”“建行”）
        bankNameMatch(accounts, rawInput)?.let { return it }

        // 5) 按类型兜底（支付宝/微信/现金唯一时可直接命中）
        val type = aiText.takeIf { it.isNotBlank() }?.let { typeOf(it) }
        if (type != null && type != AccountType.OTHER) {
            val sameType = accounts.filter { it.type == type }
            if (sameType.size == 1) return sameType.first()
            // 同类多个（如两张卡）时：如果原话没指定哪张，只在唯一银行卡时命中
            if (type != AccountType.BANK && sameType.isNotEmpty()) return sameType.first()
        }

        // 6) 最后：只有唯一账户时才默认命中，否则交给调用方决定
        return null
    }

    private fun typeOf(text: String): AccountType = when {
        text.contains("支付宝") || text.contains("花呗") || text.contains("余额宝") -> AccountType.ALIPAY
        text.contains("微信") || text.contains("零钱") -> AccountType.WECHAT
        text.contains("现金") -> AccountType.CASH
        text.contains("卡") || text.contains("银行") -> AccountType.BANK
        else -> AccountType.OTHER
    }

    private fun exactMatch(accounts: List<AccountEntity>, text: String): AccountEntity? {
        val n = normalize(text)
        if (n.isBlank()) return null
        return accounts.firstOrNull { normalize(it.name) == n }
    }

    /** 账户名包含关键词（关键词至少 2 个字，避免“卡”匹配到所有卡） */
    private fun containsMatch(accounts: List<AccountEntity>, text: String): AccountEntity? {
        val n = normalize(text)
        if (n.length < 2) return null
        val hits = accounts.filter { ac ->
            val name = normalize(ac.name)
            name.contains(n) || n.contains(name)
        }
        // 多个命中说明有歧义（卡1/卡2 都含“卡”），不猜
        return hits.singleOrNull()
    }

    /**
     * 银行简称匹配：“招行/建行/工行/农行/中行”等。
     * 账户名「建设银行卡」的首字是“建”，简称里也含“建”，据此命中；
     * 多个账户同时命中则不猜（返回 null 交给后续兜底）。
     */
    private fun bankNameMatch(accounts: List<AccountEntity>, text: String): AccountEntity? {
        val n = normalize(text)
        if (n.length < 2) return null

        // 简称 -> 银行关键词（账户名包含关键词即视为命中）
        val abbr = listOf(
            "招行" to "招商", "招商" to "招商",
            "建行" to "建设", "建设" to "建设",
            "工行" to "工商", "工商" to "工商",
            "农行" to "农业", "农业" to "农业",
            "中行" to "中国银行", "中国银行" to "中国银行",
            "交行" to "交通", "交通" to "交通",
            "邮储" to "邮储", "邮政" to "邮政",
            "浦发" to "浦发", "中信" to "中信",
            "民生" to "民生", "光大" to "光大",
            "兴业" to "兴业", "平安" to "平安",
            "华夏" to "华夏", "广发" to "广发",
        )
        val keyword = abbr.firstOrNull { (k, _) -> n.contains(k) }?.second ?: return null
        val hits = accounts.filter { normalize(it.name).contains(keyword) }
        return hits.singleOrNull()
    }

    /** 判断账户名里是否带有指定数字（避免 “卡1” 匹配到 “卡12”） */
    private fun containsNumber(name: String, tag: String): Boolean {
        val idx = name.indexOf(tag)
        if (idx < 0) return false
        // 数字后面不能再接数字，防止 tag=1 命中 “卡12”
        val after = name.getOrNull(idx + tag.length)
        return after == null || !after.isDigit()
    }
}
