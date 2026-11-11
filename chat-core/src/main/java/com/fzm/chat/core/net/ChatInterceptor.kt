package com.fzm.chat.core.net

import android.content.Context
import android.os.Build
import com.fzm.chat.router.core.CoreModule
import com.fzm.chat.router.core.CoreService
import com.fzm.chat.router.route
import com.zjy.architecture.ext.versionName
import okhttp3.Interceptor
import okhttp3.Response

/**
 * @author zhengjy
 * @since 2020/12/08
 * Description:
 */
class ChatInterceptor(private val context: Context) : Interceptor {

    private val coreService by route<CoreService>(CoreModule.SERVICE)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // 阅后即焚排查：打印 burn-confirm 请求与后端完整响应（区分前后端问题）
        val isBurnConfirm = original.url.encodedPath.contains("app/record/burn-confirm")
        if (isBurnConfirm) {
            android.util.Log.d("BurnFlow", "[HTTP] ${original.method} ${original.url}")
            original.body?.let { body ->
                val buffer = okio.Buffer()
                try {
                    body.writeTo(buffer)
                    android.util.Log.d("BurnFlow", "[HTTP] request body: ${buffer.readUtf8()}")
                } catch (e: Exception) {
                    android.util.Log.d("BurnFlow", "[HTTP] request body read failed: ${e.message}")
                }
            }
        }
        val requestBuilder = original.newBuilder().apply {
            addHeader("FZM-UUID", "uuid")
            addHeader("Fzm-Request-Source", "chat")
            addHeader("FZM-VERSION", context.versionName)
            addHeader("FZM-DEVICE", "Android")
            addHeader("FZM-DEVICE-NAME", Build.MODEL)
            addHeader("FZM-SIGNATURE", coreService?.signAuth() ?: "")
        }
        val response = chain.proceed(requestBuilder.build())
        if (isBurnConfirm) {
            android.util.Log.d("BurnFlow", "[HTTP] response code: ${response.code}")
            try {
                android.util.Log.d("BurnFlow", "[HTTP] response body: ${response.peekBody(Long.MAX_VALUE).string()}")
            } catch (e: Exception) {
                android.util.Log.d("BurnFlow", "[HTTP] response body read failed: ${e.message}")
            }
        }
        return response
    }
}