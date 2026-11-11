package com.fzm.chat.core.data.bean

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * [POST /store/app/pri-chat-record] 成功时 data 字段
 */
data class PriChatRecordData(
    @SerializedName("record_count")
    val recordCount: Int = 0,
    val records: List<PriChatRecord> = emptyList()
) : Serializable

/**
 * 单条私聊记录
 */
data class PriChatRecord(
    val logId: String = "",
    val msgId: String = "",
    val fromId: String = "",
    val targetId: String = "",
    val msgType: Int = 0,
    val createTime: Long = 0,
    val content: Map<String, Any?>? = null,
    /**
     * 阅后即焚为 {"burn":true}；普通消息可省略。必须解析，禁止丢弃。
     */
    val source: Map<String, Any?>? = null
) : Serializable
