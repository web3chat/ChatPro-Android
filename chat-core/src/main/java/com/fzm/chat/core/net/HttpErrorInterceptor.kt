package com.fzm.chat.core.net

import com.zjy.architecture.exception.ApiException
import okhttp3.Interceptor
import org.json.JSONObject

/**
 * 把 HTTP 非 2xx 响应转换成带服务端 message 的 ApiException。
 *
 * 背景：Retrofit 对非 2xx 会抛 HttpException，架构库的 handleException
 * 只会按状态码映射成「请求错误 / 服务器错误」，服务端返回的具体原因
 * （如 {"message":"Invalid verification code..."}）会被丢掉。
 *
 * 这里在 OkHttp 层提前读取响应体里的 message，抛出 ApiException，
 * handleException 会原样返回，UI 就能显示具体错误。
 */
object HttpErrorInterceptor {

    private const val MAX_BODY = 64 * 1024L

    fun interceptor(): Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) {
            val body = runCatching { response.peekBody(MAX_BODY).string() }.getOrDefault("")
            val message = parseMessage(body)
                ?: response.message.takeIf { it.isNotBlank() }
                ?: "HTTP ${response.code}"
            response.close()
            throw ApiException(response.code, message)
        }
        response
    }

    private fun parseMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val json = JSONObject(body)
            var result: String? = null
            for (key in arrayOf("message", "msg", "error", "errorMessage")) {
                val value = json.optString(key)
                if (value.isNotBlank()) {
                    result = value
                    break
                }
            }
            result
        }.getOrNull()
    }
}
