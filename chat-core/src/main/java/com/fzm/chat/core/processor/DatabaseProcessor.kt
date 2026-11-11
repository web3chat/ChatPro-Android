package com.fzm.chat.core.processor

import android.content.Context
import com.fzm.arch.connection.processor.Processor
import com.fzm.arch.connection.utils.urlKey
import com.fzm.chat.core.chain.waitForChainResult
import com.fzm.chat.core.data.AppPreference
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.ChatDatabase
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.bean.Option
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.data.local.MessageRepository
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.model.contact
import com.fzm.chat.core.data.model.toChatMessage
import com.fzm.chat.core.data.model.toMessagePO
import com.fzm.chat.core.data.msg.MsgState
import com.fzm.chat.core.chain.ChainException
import com.fzm.chat.core.data.po.*
import com.fzm.chat.core.logic.MessageSubscription
import com.fzm.chat.core.utils.toResult
import com.fzm.chat.router.route
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.router.wallet.WalletService
import com.zjy.architecture.data.Result
import com.zjy.architecture.util.logD
import com.zjy.architecture.util.logV
import dtalk.biz.Biz
import dtalk.biz.msg.Msg
import kotlinx.coroutines.*

/**
 * @author zhengjy
 * @since 2020/12/07
 * Description:消息数据库相关操作
 */
class DatabaseProcessor(
    private val context: Context,
    private val contactManager: ContactManager,
) : Processor<Biz.Message> {

    private val database: ChatDatabase
        get() = ChatDatabaseProvider.provide()

    private val service by route<WalletService>(WalletModule.SERVICE)

    override suspend fun process(server: String, message: Biz.Message): Biz.Message? {
        message.apply {
            logV("msg:[${server.urlKey()}] DB Process  |", printMsgContent(msgType, msg))
            logD(
                "MsgFlow",
                "[recv] process msgId=$msgId logId=$logId channel=$channelType from=$from target=$target " +
                    "type=$msgType time=$datetime server=${server.urlKey()}"
            )
            val payload = MessageRepository.parse(msg, context, msgType, false)
            val source = parseSource(message)
            val reference = parseReference(message)
            val state = if (MessageSubscription.ackSet.remove(logId)) {
                MsgState.SENT_AND_RECEIVE
            } else {
                MsgState.SENT
            }
            val msg = MessagePO(logId, msgId, channelType, from, target, datetime, state, msgType.number, payload, source, reference)
            // 该条消息是否被@
            var beAt = false
            when (msgType) {
                Biz.MsgType.Text -> {
                    if (payload.atList != null) {
                        for (id in payload.atList!!) {
                            if (id == AppPreference.ADDRESS || id == ChatConst.AT_ALL_MEMBERS) {
                                beAt = true
                                break
                            }
                        }
                    }
                }
                Biz.MsgType.Transfer -> {
                    payload.txStatus = "0"
                    queryTransaction(from, target, msg.toChatMessage())
                }
                Biz.MsgType.RedPacket -> {
                    database.redPacketDao().insert(RedPacketMessage(msgId, payload.packetId))
                }
                else -> {

                }
            }
            val row = database.recentSessionDao().insertMessage(msg, 1, beAt)
            logD(
                "MsgFlow",
                "[recv] insert msgId=$msgId logId=$logId row=$row beAt=$beAt state=$state " +
                    "contact=${msg.contact} duplicate=${row == -1L}"
            )
            if (row != -1L) {
                MessageSubscription.onReceiveMessage(msg.toChatMessage().apply {
                    if (msgType == Biz.MsgType.Notification_VALUE
                        && this.msg.notificationType != Msg.NotificationType.RevokeMessage_VALUE
                    ) {
                        // 除了撤回通知，其他通知不弹出提示
                        notify = false
                    }
                })
            } else {
                // 服务端回推了自己刚发出的消息（多端同步）：说明服务端已接收成功，
                // 即使 socket ack 丢失/迟到，也要把本地「发送中」纠正为已发送，避免误报失败
                try {
                    if (from == AppPreference.ADDRESS) {
                        val local = database.messageDao().findMessageByMsgId(msgId)
                        if (local != null && local.state == MsgState.SENDING) {
                            database.messageDao().updateMessage(msgId, logId, datetime, state)
                            database.recentSessionDao()
                                .updateRecentLogId(logId, msg.contact, channelType)
                            local.logId = logId
                            local.datetime = datetime
                            local.state = state
                            local.failReason = null
                            local.failDetail = null
                            MessageSubscription.onUpdateState(local)
                            logD(
                                "MsgFlow",
                                "[recv] confirm own sending msg msgId=$msgId logId=$logId state=$state"
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(
                        "MsgFlow",
                        "[recv] confirm own sending msg failed msgId=$msgId: ${e.message}"
                    )
                }
            }
        }
        return message
    }

    /**
     * 查询转账交易的详情
     */
    private fun queryTransaction(from: String, target: String, message: ChatMessage) {
        GlobalScope.launch {
            val payload = message.msg
            // 查询交易详情
            waitForChainResult(checker = { tx -> tx.status != "0" }) {
                service?.getTransactionByHash(payload.chain!!,payload.platform!!, payload.txHash!!, payload.txSymbol!!)
                    ?.toResult() ?: Result.Error(ChainException("交易查询失败"))
            }.dataOrNull()?.also { tx ->
                payload.txAmount = tx.value
                payload.txStatus = tx.status
                payload.txInvalid = false
                MessageSubscription.onMessage(Option.UPDATE_CONTENT, message)
                database.messageDao().insert(message.toMessagePO())
            }
        }
    }

    private suspend fun parseSource(message: Biz.Message): MessageSource? {
        if (!message.hasSource()) return null
        val src = message.source
        // 纯阅后即焚：服务端只带 burn，from/target 为 null
        val from = if (src.hasFrom()) {
            SourceUser(src.from.id, contactManager.getUserInfo(src.from.id, true).getRawName())
        } else {
            null
        }
        val target = if (src.hasTarget()) {
            val name = if (src.channelType == ChatConst.PRIVATE_CHANNEL) {
                contactManager.getUserInfo(src.target.id, true).getRawName()
            } else {
                contactManager.getGroupInfo(src.target.id).getRawName()
            }
            SourceUser(src.target.id, name)
        } else {
            null
        }
        // burn=false 且 from/target 为空 → 当成没有 Source，不要当成转发
        if (!src.burn && from == null && target == null) return null
        val result = MessageSource(src.channelType, from, target, src.burn)
        android.util.Log.d("BurnFlow", "[recv] parseSource: logId=${message.logId} burn=${src.burn} channel=${src.channelType} hasFrom=${src.hasFrom()} hasTarget=${src.hasTarget()}")
        return result
    }

    private fun parseReference(message: Biz.Message): Reference? {
        if (!message.hasReference()) return null
        return with(message.reference) { Reference(topic, ref) }
    }
}