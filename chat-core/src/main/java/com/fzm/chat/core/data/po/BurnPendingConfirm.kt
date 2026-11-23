package com.fzm.chat.core.data.po

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 阅后即焚待确认记录：打开详情即落库，发送 burn-confirm 成功后删除。
 */
@Entity(tableName = "burn_pending_confirm")
data class BurnPendingConfirm(
    /**
     * 消息 mid
     */
    @PrimaryKey
    val logId: Long,
    /**
     * 对端 uid（这条消息的发送者）
     */
    val targetId: String,
    /**
     * 打开详情时间（Unix 毫秒）
     */
    val openedAt: Long,
    /**
     * burn-confirm 的 type：0 私聊 / 1 群聊
     */
    val type: Int = 0
)
