package com.fzm.chat.qrlogin

import org.json.JSONObject

/**
 * PC 网页版扫码登录的二维码内容。
 *
 * 只支持 v2（ECDH，2026-10-15 起）：二维码只含 PC 临时公钥，不含任何密钥材料。
 *
 * ```json
 * {
 *   "type": "qr-login-v2",
 *   "sessionId": "...",
 *   "pubKey": "..."   // PC 临时公钥，secp256k1 33 字节压缩格式 hex（02/03 开头）
 * }
 * ```
 *
 * 旧的 v1 格式（`type=qr-login`，二维码里直接带 AES key/iv，明文风险）已下线，
 * 扫到 v1 一律按「无法识别」处理。
 */
data class QrLoginPayload(
    val sessionId: String,
    /** PC 临时公钥 hex（33 字节压缩格式） */
    val pubKey: String
) {
    companion object {

        const val TYPE_V2 = "qr-login-v2"

        /**
         * 解析二维码内容；非扫码登录类型、v1 旧格式或字段不完整时返回 null。
         */
        fun parse(raw: String?): QrLoginPayload? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                val sessionId = json.optString("sessionId")
                if (sessionId.isEmpty()) return null
                if (json.optString("type") != TYPE_V2) return null
                val pubKey = json.optString("pubKey")
                if (pubKey.isEmpty()) return null
                QrLoginPayload(sessionId, pubKey)
            } catch (e: Exception) {
                null
            }
        }
    }
}
