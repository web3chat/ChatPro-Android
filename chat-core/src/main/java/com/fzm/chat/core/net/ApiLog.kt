package com.fzm.chat.core.net

import android.util.Log
import com.google.gson.Gson
import com.zjy.architecture.data.Result

/**
 * 统一接口日志（仅 debug 包输出）。
 *
 * 格式与服务器分组接口保持一致，便于直接复制到 Postman：
 * ```
 * D/ApiLog: → chat.GetServerGroup POST https://chat.3syxin.com
 * D/ApiLog:   body={"jsonrpc":"2.0","id":1,"method":"Chain33.Query","params":[...]}
 * D/ApiLog: ← 成功 chat.GetServerGroup
 * ```
 *
 * release 包：`enabled` 为 false，所有方法直接 return，不产生日志。
 */
object ApiLog {

    private const val TAG = "ApiLog"

    private val gson by lazy { Gson() }

    val enabled: Boolean
        get() = HttpLogging.enabled

    /**
     * 请求前调用
     *
     * @param api  接口名（contract method / HTTP 路径）
     * @param url  服务器地址
     * @param body 请求体（Map/对象会转成 JSON 字符串）
     */
    fun request(api: String, url: String, body: Any? = null) {
        if (!enabled) return
        Log.d(TAG, "→ $api $url")
        if (body != null) {
            val json = if (body is String) body else gson.toJson(body)
            Log.d(TAG, "  body=$json")
        }
    }

    /**
     * 请求返回后调用
     */
    fun <T> result(api: String, result: Result<T>) {
        if (!enabled) return
        if (result.isSucceed()) {
            Log.d(TAG, "← 成功 $api")
        } else {
            Log.e(TAG, "← 失败 $api: ${result.error().message}")
        }
    }
}
