package com.fzm.chat.qrlogin

import com.fzm.chat.core.data.ChatConfig
import org.json.JSONObject

/**
 * PC 网页版扫码登录的二维码内容
 *
 * v1（AES-GCM 直接用二维码里的密钥）：
 * {
 *   "type": "qr-login",
 *   "sessionId": "...",
 *   "key": "...",   // 64 位 hex，32 字节 AES-GCM 密钥
 *   "iv": "..."     // 24 位 hex，12 字节 GCM 向量
 * }
 *
 * v2（ECDH，2026-10-15 新增；二维码只含 PC 临时公钥，无任何秘密）：
 * {
 *   "type": "qr-login-v2",
 *   "sessionId": "...",
 *   "pubKey": "..." // PC 临时公钥，secp256k1 33 字节压缩格式 hex（02/03 开头）
 * }
 */
data class QrLoginPayload(
    val sessionId: String,
    /** v1：AES-GCM 密钥 hex；v2 为空 */
    val key: String? = null,
    /** v1：GCM iv hex；v2 为空 */
    val iv: String? = null,
    /** v2：PC 临时公钥 hex；v1 为空 */
    val pubKey: String? = null
) {
    companion object {

        const val TYPE_V1 = "qr-login"
        const val TYPE_V2 = "qr-login-v2"

        /**
         * 解析二维码内容；非扫码登录类型或字段不完整时返回 null
         *
         * 灰度期间 v1/v2 两种二维码都会出现，必须同时支持。
         */
        fun parse(raw: String?): QrLoginPayload? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                val sessionId = json.optString("sessionId")
                if (sessionId.isEmpty()) return null
                when (json.optString("type")) {
                    TYPE_V1 -> {
                        val key = json.optString("key")
                        val iv = json.optString("iv")
                        if (key.isEmpty() || iv.isEmpty()) {
                            null
                        } else {
                            QrLoginPayload(sessionId, key, iv)
                        }
                    }
                    TYPE_V2 -> {
                        // 灰度开关默认关闭：未开启时不识别 v2 二维码，行为与 ECDH 改造前一致
                        if (!ChatConfig.QR_LOGIN_ECDH) return null
                        val pubKey = json.optString("pubKey")
                        if (pubKey.isEmpty()) {
                            null
                        } else {
                            QrLoginPayload(sessionId, pubKey = pubKey)
                        }
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
