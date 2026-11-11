package com.fzm.chat.qrlogin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 扫码登录接口结果 */
data class QrLoginApiResult(
    val success: Boolean,
    val message: String
)

/**
 * 扫码登录后端接口（scan-node）
 *
 * 统一响应格式：{ result, message, data }，result === 0 为成功。
 * 请求头需携带 FZM-SIGNATURE（见 QrLoginCrypto.makeFzmSignature）。
 */
object QrLoginApi {

    private const val TAG = "QrLoginApi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    /**
     * 确认登录
     *
     * @param host               服务器地址（不含末尾斜杠）
     * @param sessionId          二维码中的 sessionId
     * @param address            当前登录账号的链上地址
     * @param encryptedPrivateKey 加密后的私钥（v1：hex(密文||tag)；v2：hex(iv||密文||tag)）
     * @param privateKeyHex      当前账号私钥（仅用于本地签名，不出网）
     * @param publicKeyHex       当前账号压缩公钥（hex）
     * @param phonePubKey        v2 必传：APP 临时公钥（33 字节压缩 hex），由后端校验并透传给 PC；v1 传 null
     */
    suspend fun confirm(
        host: String,
        sessionId: String,
        address: String,
        encryptedPrivateKey: String,
        privateKeyHex: String,
        publicKeyHex: String,
        phonePubKey: String? = null
    ): QrLoginApiResult {
        val signature = QrLoginCrypto.makeFzmSignature(privateKeyHex, publicKeyHex)
            ?: return QrLoginApiResult(false, "签名失败")
        val body = JSONObject().apply {
            put("sessionId", sessionId)
            put("address", address)
            put("encryptedPrivateKey", encryptedPrivateKey)
            // v2（ECDH）：透传 APP 临时公钥；v1 不带此字段
            if (!phonePubKey.isNullOrEmpty()) {
                put("phonePubKey", phonePubKey)
            }
        }.toString()
        Log.i(TAG, "confirm 请求体: $body")
        Log.i(TAG, "confirm FZM-SIGNATURE 头: $signature")
        return post("$host/app/qr-login/confirm", signature, body)
    }

    /**
     * 取消登录
     */
    suspend fun cancel(
        host: String,
        sessionId: String,
        privateKeyHex: String,
        publicKeyHex: String
    ): QrLoginApiResult {
        val signature = QrLoginCrypto.makeFzmSignature(privateKeyHex, publicKeyHex)
            ?: return QrLoginApiResult(false, "签名失败")
        val body = JSONObject().apply {
            put("sessionId", sessionId)
        }.toString()
        return post("$host/app/qr-login/cancel", signature, body)
    }

    private suspend fun post(url: String, signature: String, body: String): QrLoginApiResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(jsonType))
                    .header("Content-Type", "application/json")
                    .header("FZM-SIGNATURE", signature)
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    Log.i(TAG, "POST $url -> ${resp.code}: ${text.take(300)}")
                    if (resp.code == 401) {
                        QrLoginApiResult(false, "鉴权失败：请检查设备时间或签名")
                    } else {
                        try {
                            val json = JSONObject(text)
                            val result = json.optInt("result", -1)
                            val message = json.optString("message", "请求失败")
                            QrLoginApiResult(result == 0, message)
                        } catch (e: Exception) {
                            QrLoginApiResult(false, "响应解析失败：HTTP ${resp.code} ${text.take(120)}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "post failed: $url", e)
                QrLoginApiResult(false, "网络错误：${e.message}")
            }
        }
}
