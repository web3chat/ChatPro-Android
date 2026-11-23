package com.fzm.chat.wallet.data

import com.fzm.chat.core.net.HttpLogging
import android.util.Log
import com.fzm.chat.core.data.ChatConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 自定义代币自动识别：直接走链上 `eth_call` 查 ERC20 的 symbol / decimals。
 *
 * 背景：钱包后端的 `Wallet.GetContractorInfo` RPC 在生产环境未注册
 * （返回 `rpc: can't find method CoinsApi.ContractorInfo`），因此改为链上直查。
 *
 * 节点：
 * - ETH：https://eth.drpc.org
 * - BNB：https://bsc.publicnode.com
 * - BTY：https://mainnet.bityuan.com/eth
 *
 * 方法选择器：
 * - symbol()   → 0x95d89b41
 * - decimals() → 0x313ce567
 */
object ContractInfoResolver {

    private const val TAG = "ContractInfo"

    private const val SELECTOR_SYMBOL = "0x95d89b41"
    private const val SELECTOR_DECIMALS = "0x313ce567"

    /** POL 未提供专用节点，先用公共 Polygon 节点兜底 */
    private const val POLYGON_NODE = "https://polygon-rpc.com"

    data class Info(val symbol: String, val decimals: String)

    private val jsonType = "application/json".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(HttpLogging.interceptor())
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @param chainUi 界面上的链名：ETH / BNB / BTY / POL
     * @param contract 合约地址（0x 开头）
     */
    suspend fun resolve(chainUi: String, contract: String): Info? = withContext(Dispatchers.IO) {
        if (contract.isBlank() || !contract.startsWith("0x", ignoreCase = true)) {
            Log.w(TAG, "非 EVM 合约地址，跳过链上查询: $contract")
            return@withContext null
        }
        val node = nodeOf(chainUi)
        Log.d(TAG, "链上查询 $chainUi -> $node, contract=$contract")

        val symbolRaw = ethCall(node, contract, SELECTOR_SYMBOL)
        val decimalsRaw = ethCall(node, contract, SELECTOR_DECIMALS)
        val symbol = decodeString(symbolRaw)
        val decimals = decodeUInt(decimalsRaw)?.toString()

        Log.d(TAG, "链上返回: symbolRaw=$symbolRaw, decimalsRaw=$decimalsRaw, symbol=$symbol, decimals=$decimals")
        if (symbol.isNullOrBlank() || decimals == null) {
            return@withContext null
        }
        Info(symbol.trim(), decimals)
    }

    private fun nodeOf(chainUi: String): String = when (chainUi.uppercase()) {
        "BTY" -> ChatConfig.WEB3_BTY_NODE
        "BNB" -> ChatConfig.WEB3_BNB_NODE
        "POL" -> POLYGON_NODE
        else -> ChatConfig.WEB3_ETH_NODE
    }

    private fun ethCall(node: String, to: String, data: String): String? {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "eth_call")
            put("params", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("to", to)
                    put("data", data)
                })
                put("latest")
            })
        }.toString()
        return try {
            val request = Request.Builder()
                .url(node)
                .post(body.toRequestBody(jsonType))
                .header("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                Log.d(TAG, "eth_call $data -> HTTP ${resp.code} ${text.take(200)}")
                if (resp.code != 200) return null
                val json = JSONObject(text)
                if (json.has("error") && !json.isNull("error")) {
                    Log.w(TAG, "eth_call error: ${json.opt("error")}")
                    return null
                }
                json.optString("result").takeIf { it.isNotEmpty() && it != "null" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "eth_call 失败: node=$node", e)
            null
        }
    }

    /**
     * 解析 ABI 编码的 string：
     * - 标准 string：offset(32) + length(32) + data
     * - 兼容部分 token 返回 bytes32（如 MKR）
     */
    private fun decodeString(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val hex = raw.removePrefix("0x")
        if (hex.length < 64) return null
        return try {
            if (hex.length == 64) {
                bytesOf(hex).toString(Charsets.UTF_8).trimEnd('\u0000').trim()
            } else {
                val offset = hex.substring(0, 64).toInt(16) * 2
                if (offset + 64 > hex.length) return null
                val length = hex.substring(offset, offset + 64).toInt(16) * 2
                val end = (offset + 64 + length).coerceAtMost(hex.length)
                bytesOf(hex.substring(offset + 64, end)).toString(Charsets.UTF_8).trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "decodeString 失败: $raw", e)
            null
        }
    }

    private fun decodeUInt(raw: String?): Long? {
        if (raw.isNullOrEmpty()) return null
        val hex = raw.removePrefix("0x").trimStart('0')
        if (hex.isEmpty()) return 0L
        return try {
            hex.toLong(16)
        } catch (e: Exception) {
            Log.w(TAG, "decodeUInt 失败: $raw", e)
            null
        }
    }

    private fun bytesOf(hex: String): ByteArray {
        val len = hex.length / 2
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
