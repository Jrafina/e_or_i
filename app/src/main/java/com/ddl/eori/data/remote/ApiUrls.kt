package com.ddl.eori.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.TimeZone

/**
 * OpenAI 兼容接口的 URL 拼装。
 *
 * 用户可能填 `https://api.openai.com`、`https://api.openai.com/v1`、
 * 甚至 `https://xx.com/v1/`，这里统一归一化，避免出现 `/v1/v1/models`。
 */
object ApiUrls {

    private val VERSION_SEGMENT = Regex("""/v\d+[a-z0-9]*$""", RegexOption.IGNORE_CASE)

    /** 归一化 Base URL：去掉尾部斜杠；若未以 /vN 结尾则补 /v1 */
    fun normalizeBaseUrl(input: String): String {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return ""
        // 补全协议头，方便用户只填域名
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
        }
        return url
    }

    /**
     * 拼接 endpoint。规则：
     * - baseUrl 已含 /vN（如 /v1、/v1beta）→ 直接拼
     * - baseUrl 未含版本段 → 自动补 /v1 再拼
     */
    fun endpoint(baseUrl: String, path: String): String {
        val base = normalizeBaseUrl(baseUrl)
        if (base.isEmpty()) return ""
        val cleanPath = path.trimStart('/')
        // base 已经以 /vN 结尾（/v1、/v1beta ...）时不再重复补版本段
        val hasVersion = VERSION_SEGMENT.containsMatchIn(base)
        return if (hasVersion) "$base/$cleanPath" else "$base/v1/$cleanPath"
    }

    fun modelsUrl(baseUrl: String): String = endpoint(baseUrl, "models")

    fun chatCompletionsUrl(baseUrl: String): String = endpoint(baseUrl, "chat/completions")
}

/**
 * 宽松 JSON 解析：模型经常输出 ```json 包裹、前后带解释文字，
 * 这里先做多级容错提取，再交给严格校验，尽可能提升解析成功率。
 */
object JsonExtract {

    private val fenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    /** 从任意文本中提取第一个合法 JSON 对象；失败返回 null */
    fun extractObject(raw: String): JsonObject? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // 1) 代码块
        fenceRegex.find(text)?.groupValues?.getOrNull(1)?.let { candidate ->
            parseObjectOrNull(candidate)?.let { return it }
        }
        // 2) 整体就是 JSON
        parseObjectOrNull(text)?.let { return it }
        // 3) 截取第一个 { 到最后一个 } 之间的内容
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start in 0 until end) {
            parseObjectOrNull(text.substring(start, end + 1))?.let { return it }
        }
        return null
    }

    private fun parseObjectOrNull(text: String): JsonObject? = runCatching {
        Json.parseToJsonElement(text.trim()).jsonObject
    }.getOrNull()

    /** 从 JsonObject 中按多个候选键取字符串（忽略大小写与下划线） */
    fun stringOf(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val direct = obj[key]
            if (direct != null) {
                primitiveToString(direct)?.let { return it }
            }
            // 忽略大小写 / 下划线匹配
            val normalizedKey = normalizeKey(key)
            val matched = obj.entries.firstOrNull { normalizeKey(it.key) == normalizedKey }?.value
            if (matched != null) primitiveToString(matched)?.let { return it }
        }
        return null
    }

    private fun normalizeKey(key: String) = key.lowercase().replace("_", "").replace("-", "")

    private fun primitiveToString(element: JsonElement): String? = runCatching {
        // 数字/字符串都取其字面量：模型可能返回 20 或 "20"
        element.jsonPrimitive.content
    }.getOrNull()

    /** 模型可能返回 "account": {"name": "支付宝"}，做一层兼容 */
    fun objectOrString(obj: JsonObject, key: String): String? {
        val element = obj[key] ?: obj.entries.firstOrNull { normalizeKey(it.key) == normalizeKey(key) }?.value
            ?: return null
        return runCatching {
            when {
                element is JsonPrimitive -> element.content
                element is JsonObject -> stringOf(element, "name", "account", "value")
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * 解析上下文：注入“今天”与本地时区，避免把相对时间交给模型瞎猜。
 */
data class ParseContext(
    val now: Long,
    val zone: TimeZone,
)

/** 模型列表响应解析：{ "data": [ { "id": "gpt-4o-mini" } ] } */
object ModelListParser {
    fun parse(body: String): List<String> = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val array = root["data"]?.jsonArray ?: root["models"]?.jsonArray ?: return emptyList()
        array.mapNotNull { item ->
            runCatching { item.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
        }.filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())
}
