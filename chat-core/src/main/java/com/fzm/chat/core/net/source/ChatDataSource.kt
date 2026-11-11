package com.fzm.chat.core.net.source

import com.fzm.chat.core.data.bean.AutoDeleteSettingData
import com.fzm.chat.core.data.bean.CleanPrivateData
import com.fzm.chat.core.data.bean.PriChatRecordData
import com.fzm.chat.core.net.model.BurnConfirmResp
import com.fzm.chat.core.net.model.RevokeBatchResp
import com.zjy.architecture.data.Result

/**
 * @author zhengjy
 * @since 2021/10/08
 * Description:
 */
interface ChatDataSource {

    /**
     * 撤回消息
     *
     * @param type  类型，0撤回私聊消息 1撤回群聊消息
     * @param logId 消息id
     */
    suspend fun revokeMessage(type: Int, logId: Long): Result<Any>

    /**
     * 批量撤回消息
     *
     * @param type   类型，0私聊 1群聊
     * @param logIds 消息id列表
     */
    suspend fun revokeMessageBatch(type: Int, logIds: List<Long>): Result<RevokeBatchResp>

    /**
     * 关注消息
     *
     * @param type  类型，0关注私聊消息 1关注群聊消息
     * @param logId 消息id
     */
    suspend fun focusMessage(type: Int, logId: Long): Result<Any>

    /**
     * 按时间范围删除与 [targetId] 的私聊记录（服务端）。
     *
     * @param dateOpt [com.fzm.chat.core.data.bean.PrivateChatCleanDateOpt]
     * @param startTime [dateOpt] 为 0 时必填，Unix 毫秒
     * @param endTime [dateOpt] 为 0 时必填，Unix 毫秒
     */
    suspend fun cleanPrivateChat(
        targetId: String,
        dateOpt: Int,
        startTime: Long? = null,
        endTime: Long? = null
    ): Result<CleanPrivateData>

    suspend fun setAutoDeletePrivate(targetId: String, option: Int): Result<AutoDeleteSettingData>

    suspend fun getAutoDeletePrivate(targetId: String): Result<AutoDeleteSettingData>

    /**
     * 分页拉取私聊历史消息
     */
    suspend fun getPriChatRecord(targetId: String, count: Int, logId: String? = null): Result<PriChatRecordData>

    /**
     * 阅后即焚确认：收方看过正文后调用
     *
     * @param targetId 对端 uid（消息发送者）
     * @param logId    消息 mid
     */
    suspend fun burnConfirm(targetId: String, logId: Long): Result<BurnConfirmResp>
}