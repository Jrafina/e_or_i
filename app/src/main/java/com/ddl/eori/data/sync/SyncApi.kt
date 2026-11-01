package com.ddl.eori.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 与 Cloudflare Worker 通信的 HTTP 客户端（只做传输，不含同步策略）。
 *
 * 所有异常都转成 [SyncError]，保证 UI 层只会拿到人类可读的中文错误。
 */
class SyncApi(
    private val http: OkHttpClient = defaultClient(),
) {
    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 协议版本，必须与服务端 Worker 的 PROTOCOL_VERSION 一致。
         * 版本 2：金额/余额均以「元」传输（两位小数）。
         * 升级单位约定时必须同步改这里和服务端，服务端会拒绝不匹配的请求。
         */
        const val PROTOCOL_VERSION = 2

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** 读取云端元信息（最后更新时间戳、记录数） */
    suspend fun meta(config: SyncConfig): SyncMetaResponse = call(
        config = config,
        path = "/sync/meta",
    ) { body ->
        json.decodeFromString(SyncMetaResponse.serializer(), body)
    }

    /**
     * 拉取云端比 [since] 更新的记录。
     * [sinceUid] 与 [since] 组成复合游标，保证同一毫秒的多条记录分页时不重不漏。
     */
    suspend fun pull(config: SyncConfig, since: Long, sinceUid: String? = null): SyncPullResponse = call(
        config = config,
        path = "/sync/pull?since=$since" + (sinceUid?.let { "&sinceUid=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""),
    ) { body ->
        json.decodeFromString(SyncPullResponse.serializer(), body)
    }

    /** 上传本地增量 */
    suspend fun push(config: SyncConfig, request: SyncPushRequest): SyncPushResponse {
        val payload = json.encodeToString(SyncPushRequest.serializer(), request)
        return call(config = config, path = "/sync/push", postBody = payload) { body ->
            json.decodeFromString(SyncPushResponse.serializer(), body)
        }
    }

    /** 校验配置是否可用（meta 接口通即可） */
    suspend fun testConnection(config: SyncConfig): SyncMetaResponse = meta(config)

    // ---------------------------------------------------------------- 内部

    private suspend fun <T> call(
        config: SyncConfig,
        path: String,
        postBody: String? = null,
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        if (!config.isReady) throw SyncError("请先填写同步地址和密钥")
        if (!config.isSecureEndpoint) {
            throw SyncError("同步地址必须使用 https://（明文 http 会泄露账单和密钥）")
        }

        val url = SyncConfig.normalize(config.endpoint) + path
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.token.trim()}")
            .header("Accept", "application/json")
            .header("X-Eori-Protocol", PROTOCOL_VERSION.toString())
            .apply {
                if (postBody != null) {
                    header("Content-Type", "application/json")
                    post(postBody.toRequestBody(JSON_MEDIA))
                } else {
                    get()
                }
            }
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw when (response.code) {
                        // 协议版本不匹配：新版服务端拒绝旧版客户端（或反之），避免金额被重复换算
                        409 -> SyncError(
                            "同步协议版本不匹配。请确认：\n" +
                                "1) 云端 Worker 已重新执行 wrangler deploy（必须是含 PROTOCOL_VERSION=2 的版本）\n" +
                                "2) App 为最新版本"
                        )
                        401, 403 -> SyncError("密钥不正确（${response.code}），请检查同步密钥")
                        404 -> SyncError("同步接口不存在（404），请检查地址是否为 Worker 根地址")
                        429 -> SyncError("请求过于频繁，请稍后再试")
                        in 500..599 -> SyncError("云端服务异常（${response.code}），请稍后再试")
                        else -> SyncError("请求失败（${response.code}）：${text.take(120)}")
                    }
                }
                runCatching { parse(text) }.getOrElse {
                    throw SyncError("云端返回格式不正确，请确认地址指向 e or i 同步 Worker")
                }
            }
        } catch (e: SyncError) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw SyncError("连接超时，请检查网络")
        } catch (e: UnknownHostException) {
            throw SyncError("无法解析同步地址，请检查地址是否正确")
        } catch (e: IOException) {
            throw SyncError("网络异常：${e.message ?: "未知错误"}")
        } catch (e: Exception) {
            throw SyncError("同步失败：${e.message ?: "未知错误"}")
        }
    }
}
