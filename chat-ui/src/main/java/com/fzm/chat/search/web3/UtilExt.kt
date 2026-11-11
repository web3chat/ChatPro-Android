package com.fzm.chat.search.web3

import android.net.Uri
import android.util.ArrayMap
import android.view.View
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

fun <T : Any?> String.jsonToMap(): ArrayMap<String, T> {
    return Gson().fromJson<ArrayMap<String, T>>(this, ArrayMap::class.java)
}


fun toJSONStr(vararg params: Pair<String, Any?>): String {
    val param = JSONObject()
    for (i in params) {
        val value = if (i.second == null) "" else i.second
        param.put(i.first, value)
    }
    return param.toString()
}

fun View.oneClick() {
    var oldTime: Long = 0
    if (System.currentTimeMillis() - oldTime  > 500) {
        setOnClickListener {

        }
    }
    oldTime = System.currentTimeMillis()

}

fun String.extractHost(): String = Uri.parse(this).host ?: "Unknown host"

fun String.toPlainStr(point: Int = 4): String {
    //BigDecimal里面放string不放double
    //stripTrailingZeros:去除尾部所有的0,并返回一个BigDecimal类型的数据,不能保证不是科学计数法
    //toPlainString:不使用科学计数法
    return BigDecimal(this).setScale(point, RoundingMode.DOWN)
        .stripTrailingZeros().toPlainString()
}
