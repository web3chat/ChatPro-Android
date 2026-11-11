package com.fzm.chat.core.repo

import com.fzm.chat.core.data.bean.AutoDeleteSettingData
import com.fzm.chat.core.data.bean.CleanPrivateData
import com.fzm.chat.core.data.bean.PriChatRecordData
import com.fzm.chat.core.data.bean.PrivateChatCleanDateOpt
import com.fzm.chat.core.net.api.ChatService
import com.fzm.chat.core.net.model.BurnConfirmResp
import com.fzm.chat.core.net.model.RevokeBatchResp
import com.fzm.chat.core.net.source.ChatDataSource
import com.zjy.architecture.data.Result
import com.zjy.architecture.ext.apiCall

/**
 * @author zhengjy
 * @since 2021/10/08
 * Description:
 */
class ChatRepository(private val service: ChatService) : ChatDataSource {

    override suspend fun revokeMessage(type: Int, logId: Long): Result<Any> {
        return apiCall { service.revokeMessage(mapOf("type" to type, "logId" to logId)) }
    }

    override suspend fun revokeMessageBatch(type: Int, logIds: List<Long>): Result<RevokeBatchResp> {
        return apiCall {
            service.revokeMessageBatch(mapOf("type" to type, "logIds" to logIds))
        }
    }

    override suspend fun focusMessage(type: Int, logId: Long): Result<Any> {
        return apiCall { service.focusMessage(mapOf("type" to type, "logId" to logId)) }
    }

    override suspend fun cleanPrivateChat(
        targetId: String,
        dateOpt: Int,
        startTime: Long?,
        endTime: Long?
    ): Result<CleanPrivateData> {
        val body = mutableMapOf<String, Any>(
            "targetId" to targetId,
            "dateOpt" to dateOpt
        )
        if (dateOpt == PrivateChatCleanDateOpt.CUSTOM) {
            body["startTime"] = requireNotNull(startTime)
            body["endTime"] = requireNotNull(endTime)
        }
        return apiCall { service.cleanPrivate(body) }
    }

    override suspend fun setAutoDeletePrivate(targetId: String, option: Int): Result<AutoDeleteSettingData> {
        return apiCall { service.setAutoDeletePrivate(mapOf("targetId" to targetId, "option" to option)) }
    }

    override suspend fun getAutoDeletePrivate(targetId: String): Result<AutoDeleteSettingData> {
        return apiCall { service.getAutoDeletePrivate(mapOf("targetId" to targetId)) }
    }

    override suspend fun getPriChatRecord(targetId: String, count: Int, logId: String?): Result<PriChatRecordData> {
        val body = mutableMapOf<String, Any>("targetId" to targetId, "count" to count)
        logId?.let { body["logId"] = it }
        return apiCall { service.getPriChatRecord(body) }
    }

    override suspend fun burnConfirm(targetId: String, logId: Long): Result<BurnConfirmResp> {
        return apiCall { service.burnConfirm(mapOf("targetId" to targetId, "logId" to logId)) }
    }
}