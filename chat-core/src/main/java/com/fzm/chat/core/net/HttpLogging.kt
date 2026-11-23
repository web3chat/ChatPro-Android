package com.fzm.chat.core.net

import android.util.Log
import com.fzm.chat.core.BuildConfig
import com.fzm.chat.core.data.AppPreference
import com.fzm.chat.core.data.ChatConfig
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * 全局 HTTP 请求日志。
 *
 * **只在 debug 包生效**：`enabled` = [BuildConfig.DEBUG]，签名 release 包恒为 false。
 * release 包里拦截器原样放行、不产生任何日志。
 *
 * 用法：`OkHttpClient.Builder().addInterceptor(HttpLogging.interceptor())`
 *
 * 输出示例：
 * ```
 * D/HttpLog: → POST https://api.example.com/app/record/burn-confirm
 * D/HttpLog:   FZM-SIGNATURE: ***
 * D/HttpLog:   ⤴ {"type":1,"targetId":"123","logId":789}
 * D/HttpLog: ← 200 (123ms) POST https://api.example.com/app/record/burn-confirm
 * D/HttpLog:   ⤵ {"result":0,"message":"操作成功","data":{}}
 * ```
 */
object HttpLogging {

    const val TAG = "HttpLog"

    /** release 包恒为 false */
    val enabled: Boolean get() = BuildConfig.DEBUG

    /** 最多窥探/打印 1MB 的 body，避免大响应卡顿 */
    private const val MAX_PEEK = 1024L * 1024

    /** 单条日志最多打印的字符数 */
    private const val MAX_CHARS = 4000

    /** 这些头部的值不打印 */
    private val REDACTED_HEADERS = setOf(
        "fzm-signature", "authorization", "cookie", "set-cookie", "x-app-token", "token"
    )

    @JvmOverloads
    fun interceptor(tag: String = TAG): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!enabled) {
            return@Interceptor chain.proceed(request)
        }

        // 合约/业务域：请求 URL/Body 由 ApiLog 打印，这里只补一条「原始响应 JSON」，
        // 避免请求重复，同时保证看到的是服务端原始报文而不是加工后的摘要。
        if (isContractCall(request.url.toString())) {
            val response = try {
                chain.proceed(request)
            } catch (e: Exception) {
                Log.e(tag, "✗ ${request.method} ${request.url}: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
            logRawResponse(tag, request, response)
            return@Interceptor response
        }

        val start = System.currentTimeMillis()
        Log.d(tag, "→ ${request.method} ${request.url}")

        request.headers.names().forEach { name ->
            val value = if (name.lowercase() in REDACTED_HEADERS) "***" else request.header(name)
            Log.d(tag, "  $name: $value")
        }

        logRequestBody(tag, request.body)

        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            Log.e(
                tag,
                "✗ ${request.method} ${request.url} 失败(${System.currentTimeMillis() - start}ms): " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            throw e
        }

        Log.d(
            tag,
            "← ${response.code} ${response.message} (${System.currentTimeMillis() - start}ms) " +
                "${request.method} ${request.url}"
        )
        logResponseBody(tag, response)
        response
    }

    private fun logRequestBody(tag: String, body: okhttp3.RequestBody?) {
        if (body == null) return
        val contentType = body.contentType()
        val printable = isPrintable(contentType?.type, contentType?.subtype)
        val length = body.contentLength()
        if (!printable || length > MAX_PEEK) {
            Log.d(
                tag,
                "  ⤴ <${contentType ?: "body"}, ${if (length >= 0) "$length bytes" else "stream"}>"
            )
            return
        }
        try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val charset = contentType?.charset() ?: Charsets.UTF_8
            Log.d(tag, "  ⤴ ${buffer.readString(charset).take(MAX_CHARS)}")
        } catch (e: Exception) {
            Log.w(tag, "  ⤴ body 读取失败: ${e.message}")
        }
    }

    private fun logResponseBody(tag: String, response: Response) {
        val contentType = response.body?.contentType()
        if (!isPrintable(contentType?.type, contentType?.subtype)) return
        try {
            // 直接输出服务端原始 JSON（不截断/不重组）
            val peek = response.peekBody(MAX_PEEK)
            Log.d(tag, "  response=${peek.string().take(MAX_CHARS)}")
        } catch (e: Exception) {
            Log.w(tag, "  ⤵ body 读取失败: ${e.message}")
        }
    }

    /** 合约/业务域：只打原始响应 JSON */
    private fun logRawResponse(tag: String, request: okhttp3.Request, response: Response) {
        val contentType = response.body?.contentType()
        if (!isPrintable(contentType?.type, contentType?.subtype)) {
            Log.d(tag, "← ${response.code} ${request.url}（响应非文本，跳过 body）")
            return
        }
        try {
            val raw = response.peekBody(MAX_PEEK).string()
            Log.d(tag, "← ${response.code} ${request.url}")
            Log.d(tag, "  response=$raw")
        } catch (e: Exception) {
            Log.w(tag, "← ${response.code} ${request.url} 读取响应失败: ${e.message}")
        }
    }

    /** 文本、JSON、XML、表单才打印；图片/文件/流不读 */
    private fun isPrintable(type: String?, subtype: String?): Boolean {
        if (type == "text") return true
        val sub = subtype.orEmpty()
        return sub.contains("json") || sub.contains("xml") || sub.contains("form")
    }

    /**
     * 已由 [ApiLog] 单独记录的接口：合约服务（CONTRACT_URL）与业务服务（CENTRALIZED_SERVER）。
     *
     * 这些请求跳过 HttpLog，避免同一次请求打印两遍 URL/Body。
     */
    private fun isContractCall(url: String): Boolean {
        val contract = AppPreference.CONTRACT_URL
        if (contract.isNotEmpty() && url.startsWith(contract)) return true
        val business = ChatConfig.CENTRALIZED_SERVER
        return business.isNotEmpty() && url.startsWith(business)
    }
}
