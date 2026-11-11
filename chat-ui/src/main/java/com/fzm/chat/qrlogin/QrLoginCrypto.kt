package com.fzm.chat.qrlogin

import android.util.Base64
import android.util.Log
import com.fzm.chat.core.crypto.ECCUtils
import com.fzm.chat.core.crypto.ECCUtils.toBytes
import com.fzm.chat.core.utils.CipherUtils
import wallet.core.jni.Curve
import wallet.core.jni.Hash
import wallet.core.jni.PrivateKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 扫码登录所需的加解密与签名工具（与 PC 端逐字节对齐）
 */
object QrLoginCrypto {

    private const val TAG = "QrLoginCrypto"

    /**
     * AES-256-GCM 加密私钥
     *
     * 输出：hex(ciphertext || authTag(16))，与 PC 端 js-crypto-aes 输出一致。
     * JCA 中 AES/GCM/NoPadding 的 doFinal 输出即为「密文 + 认证标签」。
     */
    fun aesGcmEncrypt(privateKeyHex: String, keyHex: String, ivHex: String): String? {
        return try {
            val key = hexToBytes(keyHex)
            val iv = hexToBytes(ivHex)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(privateKeyHex.toByteArray(Charsets.UTF_8))
            bytesToHex(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "aesGcmEncrypt failed", e)
            null
        }
    }

    /**
     * 扫码登录 v2（ECDH）加密私钥
     *
     * 1. 生成临时密钥对 b/B（secp256k1，B 为 33 字节压缩公钥）
     * 2. shared = ECDH(b, PC 公钥) 的 x 坐标（规范为 32 字节大端，与聊天 E2E computeSharedKey 一致）
     * 3. aesKey = SHA256(shared)
     * 4. AES-256-GCM 加密（iv 随机 12 字节），输出 hex(iv || 密文 || tag)
     *
     * @param pcPubKeyHex 二维码里的 PC 临时公钥（33 字节压缩 hex）
     * @return Pair(encryptedPrivateKeyHex, phonePubKeyHex)；失败返回 null
     */
    fun ecdhEncryptPrivateKey(privateKeyHex: String, pcPubKeyHex: String): Pair<String, String>? {
        return try {
            // 1. 临时密钥对
            val keyPair = ECCUtils.genECKeyPair()
            val phonePrivHex = bytesToHex(padTo32(keyPair.private.toBytes()))
            val phonePubHex = bytesToHex(keyPair.public.toBytes(true))
            // 2. ECDH 共享密钥（x 坐标）
            val sharedRaw = CipherUtils.generateDHSessionKey(phonePrivHex, pcPubKeyHex)
                ?: return null
            val shared = padTo32(sharedRaw)
            // 3. AES 密钥 = SHA256(shared)
            val aesKey = Hash.sha256(shared)
            // 4. AES-256-GCM：iv 随机 12 字节，输出 hex(iv || 密文 || tag)
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(privateKeyHex.toByteArray(Charsets.UTF_8))
            bytesToHex(iv + encrypted) to phonePubHex
        } catch (e: Exception) {
            Log.e(TAG, "ecdhEncryptPrivateKey failed", e)
            null
        }
    }

    /**
     * 规范为 32 字节大端整数（ECDH x 坐标 / 私钥标量）：
     * 库去掉前导零时左补零；带符号字节（0x00 前缀）等多余前缀时取低 32 字节
     */
    private fun padTo32(raw: ByteArray): ByteArray = when {
        raw.size == 32 -> raw
        raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
        else -> ByteArray(32 - raw.size) + raw
    }

    /**
     * 生成 FZM-SIGNATURE
     *
     * message   = String(System.currentTimeMillis()) + "*"
     * msgHash   = SHA256(UTF8(message))
     * signature = secp256k1 ECDSA(msgHash, privateKey)  // 低 s、带 recovery id
     *            = r(32) || s(32) || recid(1)，共 65 字节
     * token     = Base64(signature) + "#" + message + "#" + publicKeyHex
     *
     * 服务端允许签名时间戳在 [-1 分钟, +5 分钟] 窗口内，设备时间需同步。
     */
    fun makeFzmSignature(privateKeyHex: String, publicKeyHex: String): String? {
        return try {
            val message = "${System.currentTimeMillis()}*"
            val msgHash = Hash.sha256(message.toByteArray(Charsets.UTF_8))
            val pk = PrivateKey(hexToBytes(privateKeyHex))
            // TrustWalletCore secp256k1 可恢复签名：r||s||recoveryId，s 为低 s 规范值
            val signature = pk.sign(msgHash, Curve.SECP256K1)
            val token = Base64.encodeToString(signature, Base64.NO_WRAP) +
                    "#" + message + "#" + publicKeyHex
            token
        } catch (e: Exception) {
            Log.e(TAG, "makeFzmSignature failed", e)
            null
        }
    }

    /**
     * 由私钥推导 33 字节压缩公钥 hex（服务端同时兼容 65 字节非压缩）
     */
    fun compressedPublicKeyHex(privateKeyHex: String): String? {
        return try {
            val pk = PrivateKey(hexToBytes(privateKeyHex))
            val pub = pk.getPublicKeySecp256k1(true)
            bytesToHex(pub.data())
        } catch (e: Exception) {
            Log.e(TAG, "compressedPublicKeyHex failed", e)
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
