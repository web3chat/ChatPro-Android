package com.fzm.chat.core.net.api

import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.bean.AutoDeleteSettingData
import com.fzm.chat.core.data.bean.CleanPrivateData
import com.fzm.chat.core.data.bean.PriChatRecordData
import com.fzm.chat.core.net.model.BurnConfirmResp
import com.fzm.chat.core.net.model.RevokeBatchResp
import com.zjy.architecture.net.HttpResult
import me.jessyan.retrofiturlmanager.RetrofitUrlManager
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * @author zhengjy
 * @since 2021/10/08
 * Description:
 */
@JvmSuppressWildcards
interface ChatService {

    /**
     * 撤回消息
     *
     * @param map type：类型，0撤回私聊消息 1撤回群聊消息
     *            logId：消息id
     */
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CHAT_DOMAIN])
    @POST("/app/record/revoke")
    suspend fun revokeMessage(@Body map: Map<String, Any>): HttpResult<Any>

    /**
     * 批量撤回消息
     *
     * @param body type：类型，0私聊 1群聊
     *             logIds：消息id列表（1～50条）
     */
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CHAT_DOMAIN])
    @POST("/app/record/revoke-batch")
    suspend fun revokeMessageBatch(@Body body: Map<String, Any>): HttpResult<RevokeBatchResp>

    /**
     * 关注消息
     *
     * @param map type：类型，0撤回私聊消息 1撤回群聊消息
     *            logId：消息id
     */
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CHAT_DOMAIN])
    @POST("/app/record/focus")
    suspend fun focusMessage(@Body map: Map<String, Any>): HttpResult<Any>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CHAT_DOMAIN])
    @POST("/app/record/clean-private")
    suspend fun cleanPrivate(@Body map: Map<String, Any>): HttpResult<CleanPrivateData>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CHAT_DOMAIN])
    @POST("/app/record/auto-delete/set")
    suspend fun setAutoDeletePrivate(@Body map: Map<String, Any>): HttpResult<AutoDeleteSettingData>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CHAT_DOMAIN])
    @POST("/app/record/auto-delete/get")
    suspend fun getAutoDeletePrivate(@Body map: Map<String, Any>): HttpResult<AutoDeleteSettingData>

    /**
     * 阅后即焚确认：收方看过正文后调用
     *
     * @param map targetId：对端 uid（消息发送者）
     *            logId：消息 mid
     */
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CHAT_DOMAIN])
    @POST("/app/record/burn-confirm")
    suspend fun burnConfirm(@Body map: Map<String, Any>): HttpResult<BurnConfirmResp>

    /**
     * 分页拉取私聊历史消息（用于打开聊天时同步）
     *
     * @param body targetId：对方用户ID
     *             count：拉取条数（1~100）
     *             logId：分页游标（可选，不传从最新开始）
     */
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CHAT_DOMAIN])
    @POST("/store/app/pri-chat-record")
    suspend fun getPriChatRecord(@Body body: Map<String, Any>): HttpResult<PriChatRecordData>
}