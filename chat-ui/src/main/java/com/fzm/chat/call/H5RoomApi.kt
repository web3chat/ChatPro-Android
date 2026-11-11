package com.fzm.chat.call

import com.fzm.chat.core.net.TrustAllCertificate
import com.fzm.chat.core.net.TrustAllHostnameVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * h5_room 建房接口（对应文档 POST /api/v1/room/create）
 *
 * 响应示例：{"code":0,"room":"abc234","pin":"8866","mode":"voice"}
 */
object H5RoomApi {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .apply {
                if (CallConfig.TRUST_SELF_SIGNED) {
                    // 局域网自签名证书联调：放行证书校验；
                    // 该 client 只用于访问 h5_room 服务（CallConfig.BASE_URL），与通话 WebView 的放行策略保持一致
                    TrustAllCertificate.createSSLSocketFactory()?.let {
                        sslSocketFactory(it, TrustAllCertificate())
                    }
                    hostnameVerifier(TrustAllHostnameVerifier())
                }
            }
            .build()
    }

    data class Room(val room: String, val pin: String, val mode: String)

    /**
     * 创建房间（与 H5 建房请求保持一致：只传 mode）
     *
     * @param mode voice / video
     * @return 房间信息，失败返回 null
     */
    suspend fun createRoom(mode: String): Room? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("mode", mode)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("${CallConfig.BASE_URL}/api/v1/room/create")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val text = resp.body?.string() ?: return@withContext null
                val json = JSONObject(text)
                if (json.optInt("code", -1) != 0) return@withContext null
                val room = json.optString("room")
                val pin = json.optString("pin")
                if (room.isEmpty() || pin.isEmpty()) return@withContext null
                Room(room, pin, json.optString("mode", mode))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
