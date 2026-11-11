package com.fzm.chat.core.data.bean

import com.google.gson.annotations.SerializedName
import java.io.Serializable

class BindAccount : Serializable {
    @SerializedName("chataddress")
    val chataddress: String = ""
    @SerializedName("phone")
    val phone: String = ""
    @SerializedName("wechat_id")
    val wechat_id: String = ""
    @SerializedName("xiaohongshu_id")
    val xiaohongshu_id: String = ""
    @SerializedName("douyin_id")
    val douyin_id: String = ""
    /** query-chataddress 接口返回的关键字 */
    @SerializedName("keyword")
    val keyword: String = ""
    /** query-chataddress 接口返回的匹配字段：phone / wechat_id / xiaohongshu_id / douyin_id */
    @SerializedName("matched_field")
    val matched_field: String = ""
}