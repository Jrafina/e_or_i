package com.ddl.eori.data.remote

import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.local.TxType
import com.ddl.eori.domain.ParseError
import com.ddl.eori.domain.defaultName
import com.ddl.eori.domain.guessAccountType
import com.ddl.eori.util.Money
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** AI 解析的原始结果（未绑定账户 ID） */
data class AiParseResult(
    val amountCents: Long,
    val type: TxType,
    val accountHint: String,
    val accountType: AccountType,
    val note: String,
    val occurredAt: Long,
)

/**
 * OpenAI 兼容接口客户端。
 *
 * 设计要点：
 * - 只依赖 OkHttp + kotlinx.serialization，不引入 Retrofit，减少体积与构建复杂度。
 * - 所有网络调用在 Dispatchers.IO；任何异常都转换为 ParseError，绝不向上抛。
 * - 超时较短（连接 10s / 读 25s），避免用户长时间等待解析中状态。
 */
class AiClient(
    private val http: OkHttpClient = defaultClient(),
) {
    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    // ---------------------------------------------------------------- 验证配置

    /**
     * 拉取模型列表：GET {base}/models。
     * 返回 Result：成功为模型 id 列表，失败为带明确信息的 ParseError。
     */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = ApiUrls.modelsUrl(baseUrl)
            require(url.isNotBlank()) { "Base URL 不能为空" }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Accept", "application/json")
                .get()
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException(response.code, summarizeError(body))
                }
                val models = ModelListParser.parse(body)
                if (models.isEmpty()) {
                    throw ApiException(response.code, "返回格式不是 OpenAI 兼容的模型列表")
                }
                models
            }
        }.recoverCatching { e ->
            throw e.asParseError().asException()
        }
    }

    // ---------------------------------------------------------------- 自然语言解析

    /**
     * 解析一句话 -> 结构化账单。
     * 失败时返回 [Result.failure]（携带 ParseError），由 ViewModel 降级手动表单。
     */
    suspend fun parseTransaction(
        rawInput: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        /** 用户已有账户名列表：交给模型做精确匹配，避免“卡2”被记到“卡1” */
        accountNames: List<String> = emptyList(),
    ): Result<AiParseResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
                throw ApiException(0, "未配置 AI")
            }
            val nowText = formatIsoLocal(now, zone)
            val weekday = weekdayName(now, zone)
            val body = buildChatBody(model, rawInput, nowText, weekday, accountNames)

            val request = Request.Builder()
                .url(ApiUrls.chatCompletionsUrl(baseUrl))
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException(response.code, summarizeError(text))
                }
                val content = extractAssistantContent(text)
                    ?: throw ContentException("响应中缺少 choices[0].message.content")
                parseContent(content, now, zone)
            }
        }.recoverCatching { e ->
            throw e.asParseError().asException()
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private fun buildChatBody(
        model: String,
        rawInput: String,
        nowText: String,
        weekday: String,
        accountNames: List<String>,
    ): String {
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", 0.1)
            put("stream", false)
            // 兼容支持 json_object 的服务；不支持时该字段通常被忽略
            putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", Prompts.systemPrompt(nowText, weekday, accountNames))
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", Prompts.USER_TEMPLATE.format(rawInput))
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    /** 从 chat/completions 响应中取出 message.content（兼容 content 为数组的分段格式） */
    private fun extractAssistantContent(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val message = first["message"] as? JsonObject ?: return null
        return when (val content = message["content"]) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.mapNotNull { part ->
                val text = (part as? JsonObject)?.get("text") as? JsonPrimitive
                text?.content
            }.joinToString("")
            else -> null
        }
    }

    /**
     * 校验并转换模型输出。校验规则（需求 3）：
     * - amount > 0
     * - type 合法（income/expense）
     * - account 不存在时归为“其他”
     * - occurredAt 非法时回落到当前时间
     */
    private fun parseContent(content: String, now: Long, zone: TimeZone): AiParseResult {
        val obj = JsonExtract.extractObject(content)
            ?: throw ContentException("返回内容不是 JSON 对象")

        if (JsonExtract.stringOf(obj, "error") != null) {
            throw UnparsableException("模型未能识别金额")
        }

        val amountRaw = JsonExtract.stringOf(obj, "amount", "money", "price")
            ?: throw UnparsableException("缺少 amount 字段")
        val amountCents = Money.parseToCents(amountRaw)
            ?: throw UnparsableException("金额非法：$amountRaw")

        val typeRaw = JsonExtract.stringOf(obj, "type", "direction")?.trim()?.lowercase()
        val type = when {
            typeRaw == null -> TxType.EXPENSE
            typeRaw.startsWith("income") || typeRaw.contains("收入") || typeRaw == "in" -> TxType.INCOME
            typeRaw.startsWith("expense") || typeRaw.contains("支出") || typeRaw.contains("消费") -> TxType.EXPENSE
            else -> throw ContentException("收支类型非法：$typeRaw")
        }

        val accountRaw = JsonExtract.objectOrString(obj, "account")?.trim().orEmpty()
        val accountType = normalizeAccount(accountRaw)

        val note = JsonExtract.stringOf(obj, "note", "remark", "desc", "description")
            ?.trim()?.take(40).orEmpty()

        val occurredRaw = JsonExtract.stringOf(obj, "occurredAt", "occurred_at", "time", "date")
        val occurredAt = parseOccurredAt(occurredRaw, now, zone)

        return AiParseResult(
            amountCents = amountCents,
            type = type,
            accountHint = accountRaw.ifBlank { accountType.defaultName() },
            accountType = accountType,
            note = note,
            occurredAt = occurredAt,
        )
    }

    /** 账户名归一到 5 种类型；不认识的归为“其他” */
    private fun normalizeAccount(raw: String): AccountType {
        if (raw.isBlank()) return AccountType.OTHER
        return when {
            raw.contains("支付宝") || raw.contains("花呗") || raw.contains("余额宝") -> AccountType.ALIPAY
            raw.contains("微信") || raw.contains("零钱") -> AccountType.WECHAT
            raw.contains("现金") -> AccountType.CASH
            raw.contains("银行") || raw.contains("卡") -> AccountType.BANK
            else -> guessAccountType(raw).takeIf { it != AccountType.OTHER } ?: AccountType.OTHER
        }
    }

    /** 解析模型给出的本地时间字符串；失败回落到 now */
    private fun parseOccurredAt(raw: String?, now: Long, zone: TimeZone): Long {
        if (raw.isNullOrBlank()) return now
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm",
            "yyyy/MM/dd",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA).apply {
                    timeZone = zone
                    isLenient = false
                }.parse(raw)
            }.getOrNull()
            if (parsed != null) {
                // 防御模型给出未来时间（如把“明天”当今天）：超过当前 1 天则回落到 now
                val result = parsed.time
                return if (result > now + 24 * 60 * 60 * 1000L) now else result
            }
        }
        return now
    }

    private fun formatIsoLocal(time: Long, zone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).apply { timeZone = zone }.format(Date(time))

    private fun weekdayName(time: Long, zone: TimeZone): String {
        val names = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val cal = Calendar.getInstance(zone).apply { timeInMillis = time }
        return names[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }

    /** 从错误响应体里提取可读信息（OpenAI 风格为 {"error":{"message":...}}） */
    private fun summarizeError(body: String): String {
        val message = runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
            val error = root["error"] as? JsonObject
            (error?.get("message") as? JsonPrimitive)?.content
                ?: (root["message"] as? JsonPrimitive)?.content
        }.getOrNull()
        return (message ?: body).take(180).ifBlank { "无响应内容" }
    }
}

// ---------------------------------------------------------------- 异常类型

class ApiException(val code: Int, val detail: String) : Exception(detail)
class ContentException(detail: String) : Exception(detail)
class UnparsableException(detail: String) : Exception(detail)

/** 内部异常 -> 领域错误 */
fun Throwable.toParseError(): ParseError = when (this) {
    is ParseFailure -> error
    is UnparsableException -> ParseError.Unparsable
    is ContentException -> ParseError.InvalidContent(message.orEmpty())
    is ApiException -> when (code) {
        0 -> ParseError.NotConfigured
        else -> ParseError.Http(code, detail)
    }
    is java.net.SocketTimeoutException -> ParseError.Network("请求超时")
    is java.net.UnknownHostException -> ParseError.Network("无法连接服务器")
    is java.io.IOException -> ParseError.Network(message ?: "网络异常")
    else -> ParseError.Network(message ?: "未知错误")
}

/** Throwable -> 领域错误（已是 ParseFailure 时直接取原错误） */
fun Throwable.asParseError(): ParseError = when (this) {
    is ParseFailure -> error
    else -> toParseError()
}

/** 用于在 Result 中携带领域错误 */
class ParseFailure(val error: ParseError) : Exception(error.toMessage())

fun ParseError.asException(): Throwable = ParseFailure(this)
