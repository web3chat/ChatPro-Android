package com.fzm.chat.core.net

import android.util.Log
import com.fzm.chat.core.crypto.decrypt
import com.fzm.chat.core.data.bean.GroupInfoTO
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zjy.architecture.net.HttpResult
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * @author zhengjy
 * @since 2021/11/05
 * Description:
 */
class DecryptInterceptor(private val gson: Gson) : Interceptor {

    private val paths = arrayOf(
        "group/app/group-list",
        "group/app/group-info",
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val index = paths.matches(request.url.encodedPath)
        if (index != -1) {
            // 打印完整请求参数
            Log.d("DecryptInterceptor", "=== API Request: ${paths[index]} ===")
            Log.d("DecryptInterceptor", "Method: ${request.method}")
            Log.d("DecryptInterceptor", "URL: ${request.url}")
            Log.d("DecryptInterceptor", "Headers: ${request.headers}")
            val requestBody = request.body
            if (requestBody != null) {
                val buffer = okio.Buffer()
                requestBody.writeTo(buffer)
                Log.d("DecryptInterceptor", "Body: ${buffer.readUtf8()}")
            } else {
                Log.d("DecryptInterceptor", "Body: (empty)")
            }
            Log.d("DecryptInterceptor", "=== End Request ===")
        }
        val response = chain.proceed(request)
        if (index == -1) {
            return response
        } else {
            val body = response.body?.string() ?: ""
            val decBody = when (paths[index]) {
                "group/app/group-list" -> {
                    try {
                        val wrapper = gson.fromJson<HttpResult<GroupInfoTO.Wrapper>>(
                            body,
                            object : TypeToken<HttpResult<GroupInfoTO.Wrapper>>() {}.type
                        )
                        wrapper.data.groups.forEach {
                            it.name = it.name.decrypt(it.key)
                        }
                        gson.toJson(wrapper)
                    } catch (e: Exception) {
                        body
                    }
                }
                "group/app/group-info" -> {
                    try {
                        val result = gson.fromJson<HttpResult<GroupInfoTO>>(
                            body,
                            object : TypeToken<HttpResult<GroupInfoTO>>() {}.type
                        )
                        result.data.apply { name = name.decrypt(key) }
                        gson.toJson(result)
                    } catch (e: Exception) {
                        body
                    }
                }
                else -> body
            }
            val mediaType = response.body?.contentType()
            return response.newBuilder().body(ResponseBody.create(mediaType, decBody)).build()
        }
    }

    private fun Array<String>.matches(str: String?): Int {
        if (str == null) return -1
        for (index in this.indices) {
            if (str.contains(get(index))) {
                return index
            }
        }
        return -1
    }
}