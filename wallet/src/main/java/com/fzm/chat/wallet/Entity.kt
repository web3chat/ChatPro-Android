package com.fzm.chat.wallet

import android.util.Log
import com.fzm.wallet.sdk.BuildConfig
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject


fun toRequestBody(method: String, vararg params: Pair<String, Any?>): RequestBody {
    return RequestBody.create(
        "application/json".toMediaTypeOrNull(),
        toJSONParam(method, *params).toString()
    )
}

fun toJSONObject(vararg params: Pair<String, Any?>): JSONObject {
    val param = JSONObject()
    for (i in params) {
        val value = if (i.second == null) "" else i.second
        param.put(i.first, value)
    }
    return param
}

fun toJSONParam(method: String, vararg params: Pair<String, Any?>): JSONObject {
    val param = JSONObject()
    val array = JSONArray()
    val obj = JSONObject()
    for (i in params) {
        val value = if (i.second == null) "" else i.second
        obj.put(i.first, value)
    }
    array.put(obj)
    param.put("id", 1)
    param.put("method", method)
    param.put("params", array)
    if (BuildConfig.DEBUG) {
        Log.v("param：", param.toString())
    }
    return param
}