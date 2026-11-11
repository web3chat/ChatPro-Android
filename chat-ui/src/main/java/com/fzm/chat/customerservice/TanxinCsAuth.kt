package com.fzm.chat.customerservice

import android.os.SystemClock
import android.util.Log
import com.fzm.chat.core.session.data.UserPreference
import com.fzm.chat.search.meta.WalletSigner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.web3j.crypto.Credentials
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 谈信 × BTY 智能客服（https://aikf.bityuan.com）SIWE 静默登录
 *
 * 对接文档《谈信 × BTY 智能客服 —— App 对接说明》（2026-09-24 版，含 §3.6 登录态失效自愈）：
 * ① GET  /api/auth/nonce?address=<EVM 地址> → `siwe_message`（一次性 nonce，30 分钟有效，勿缓存）
 * ② 用账户私钥（发帖/聊天同一把，[UserPreference.PRI_KEY]）对原文做 EIP-191（personal_sign）签名，原文一字不改
 * ③ POST /api/auth/login/user {address, signature, session_id, tab_id} → 凭证 data（交给页面）
 *
 * 要点：
 * - 地址取账户私钥对应的 EVM（0x）地址（[Credentials.create]），与浏览器钱包登录一致（文档 §10）
 * - **每次调用都当场新签**：不缓存原文 / 签名 / 凭证；页面在 401 自愈时会再次调用（文档 §3.6 / §8-3）
 * - `session_id` 持久化在 [UserPreference.CS_SESSION_ID]，下次登录带上以续用同一段会话（文档 §5）
 * - 限流：nonce 与 login 都计数，异常不循环重试；仅 "签名原文已过期" 重取一次原文（文档 §2.3）
 * - 整轮预算 [TOTAL_BUDGET_MS]：页面最多等 15s（文档 §3.1），超时前如实返回 null
 * - 任何失败返回 null，页面会显示自己的登录页，功能仍可用（文档 §7）
 * - 日志不打印私钥 / 签名 / token（文档 §8-8）
 */
object TanxinCsAuth {

    private const val TAG = "TanxinCs"

    /** 整轮（nonce → 签名 → login，含一次过期重试）预算；页面最多等 15s，此处留余量 */
    private const val TOTAL_BUDGET_MS = 12_000L

    /** 剩余预算低于该值时不再发起新的 HTTP 调用（保证按时返回 null） */
    private const val STEP_MIN_MS = 2_000L

    /** 客服站点地址（打开页面时附带 ?cs_host=tanxin-app，仅用于顶栏形态兜底） */
    const val SITE = "https://aikf.bityuan.com"

    private const val PATH_NONCE = "/api/auth/nonce"
    private const val PATH_LOGIN = "/api/auth/login/user"

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 当前账户私钥对应的 EVM（0x）地址；未登录 / 私钥缺失时返回 null。
     *
     * 纯本地计算（无网络、无落盘），供页面 `getAddress()` 调用（文档要求 3 秒内返回）。
     */
    fun currentAddress(preference: UserPreference): String? {
        val priKey = preference.PRI_KEY
        if (priKey.isEmpty()) return null
        return try {
            Credentials.create(priKey).address
        } catch (e: Exception) {
            Log.w(TAG, "currentAddress: invalid PRI_KEY")
            null
        }
    }

    /**
     * 本标签页标识：生成一个 UUID 后长期复用（参与 LiveKit identity `{uid}-{tab_id}`，文档 §5）
     */
    fun tabId(preference: UserPreference): String {
        val existing = preference.CS_TAB_ID
        if (existing.isNotEmpty()) return existing
        val id = UUID.randomUUID().toString()
        preference.CS_TAB_ID = id
        return id
    }

    /**
     * 三步登录；**阻塞调用，勿在主线程执行**。
     *
     * 每次调用都是一轮全新的 nonce → 签名 → login（不缓存任何一步），
     * 满足文档 §3.6 / §8-3「getAuth 随时可能被再调用，每次都当场新签」。
     *
     * @return 成功返回凭证 `data` 的 JSON 字符串（原样交给页面）；任何失败返回 null
     */
    fun login(preference: UserPreference, tabId: String): String? {
        val priKey = preference.PRI_KEY
        if (priKey.isEmpty()) {
            Log.w(TAG, "login: PRI_KEY empty")
            return null
        }
        val address = try {
            Credentials.create(priKey).address
        } catch (e: Exception) {
            Log.w(TAG, "login: invalid PRI_KEY")
            return null
        }

        val deadlineAt = SystemClock.elapsedRealtime() + TOTAL_BUDGET_MS
        var attempt = 0
        while (true) {
            if (deadlineAt - SystemClock.elapsedRealtime() < STEP_MIN_MS) {
                Log.w(TAG, "login: budget exhausted")
                return null
            }
            // ① 取原文（一次性 nonce，每次登录都重新取）
            val siwe = requestNonce(address) ?: return null
            // ② 静默签名（EIP-191，原文一字不改）
            val signature = sign(siwe, priKey) ?: return null
            if (deadlineAt - SystemClock.elapsedRealtime() < STEP_MIN_MS) return null
            // ③ 提交登录（带上次的 session_id 续用同一段会话，文档 §5）
            val resp = requestLogin(address, signature, preference.CS_SESSION_ID, tabId)
                ?: return null
            // 原文过期 / 已被用过：重新取原文再签一次（只重试这一次，文档 §2.3）
            if (isNonceExpired(resp) && attempt == 0) {
                attempt++
                Log.d(TAG, "login: nonce expired, retry once")
                continue
            }
            return handleLoginResponse(resp, preference)
        }
    }

    /** 校验 login 响应（HTTP 200 + code，文档 §2.3 / §2.4），成功后记住 session_id */
    private fun handleLoginResponse(resp: JSONObject, preference: UserPreference): String? {
        val code = resp.optInt("code", -1)
        if (code != 0) {
            // 失败同样是 HTTP 200 + code；只打 code/message，不打印敏感字段
            Log.w(TAG, "login failed: code=$code message=${resp.optString("message")}")
            return null
        }
        val data = resp.optJSONObject("data") ?: return null
        // 字段完整性校验（文档 §2.4），不全就当登录失败处理
        val complete = data.optString("token").isNotEmpty() &&
                data.optString("livekit_token").isNotEmpty() &&
                data.optString("room_name").isNotEmpty() &&
                data.optString("eth_address").isNotEmpty() &&
                data.optLong("uid") > 0 &&
                data.optString("role") == "user"
        if (!complete) {
            Log.w(TAG, "login: incomplete data")
            return null
        }
        // 记住 session_id 供下次续用同一段会话（文档 §5）
        data.optString("session_id").takeIf { it.isNotEmpty() }?.let {
            preference.CS_SESSION_ID = it
        }
        return data.toString()
    }

    // ==================== HTTP ====================

    private fun requestNonce(address: String): String? {
        return try {
            val request = Request.Builder()
                .url("$SITE$PATH_NONCE?address=$address")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.optInt("code", -1) != 0) {
                    Log.w(TAG, "nonce failed: code=${json.optInt("code", -1)}")
                    return null
                }
                json.optJSONObject("data")?.optString("siwe_message")?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "nonce error: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun requestLogin(
        address: String,
        signature: String,
        sessionId: String,
        tabId: String
    ): JSONObject? {
        return try {
            val body = JSONObject()
                .put("address", address)
                .put("signature", signature)
                .put("session_id", sessionId)
                .put("tab_id", tabId)
            val request = Request.Builder()
                .url("$SITE$PATH_LOGIN")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: return null
                JSONObject(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "login http error: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun isNonceExpired(resp: JSONObject): Boolean {
        if (resp.optInt("code", -1) != 400) return false
        val msg = resp.optString("message")
        return msg.contains("过期") || msg.contains("expired", ignoreCase = true)
    }

    private fun sign(text: String, priKey: String): String? {
        return try {
            // EIP-191（personal_sign），r|s|v（v 为 27/28）
            WalletSigner.signPersonalMessage(text, priKey)
        } catch (e: Exception) {
            Log.w(TAG, "sign error: ${e.javaClass.simpleName}")
            null
        }
    }
}
