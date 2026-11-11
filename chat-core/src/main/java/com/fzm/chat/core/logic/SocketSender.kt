package com.fzm.chat.core.logic

import chat33.comet.Socket
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.data.ChatDatabase
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.model.contact
import com.fzm.arch.connection.Disposable
import com.fzm.chat.core.data.msg.MsgState
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.arch.connection.socket.ConnectionManager
import com.fzm.arch.connection.type.ConfirmType
import com.fzm.arch.connection.utils.extra
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.bean.getPubKey
import com.fzm.chat.core.utils.buildProto
import com.fzm.chat.core.data.msg.SendFailReason
import com.zjy.architecture.util.logD
import com.zjy.architecture.util.logE
import dtalk.biz.Biz
import dtalk.proto.Api
import kotlinx.coroutines.*
import java.io.Serializable
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * @author zhengjy
 * @since 2020/12/08
 * Description:
 */
class SocketSender(
    private val contactManager: ContactManager,
    private val delegate: LoginDelegate,
    private val manager: ConnectionManager
) : Disposable, CoroutineScope {

    companion object {
        private val waitingAckQueue = Collections.synchronizedList(mutableListOf<SendingMessage>())

        /**
         * 指定消息是否正在发送中
         */
        fun isSending(msgId: String) = waitingAckQueue.any { it.message.msgId == msgId }
    }

    private val database: ChatDatabase
        get() = ChatDatabaseProvider.provide()

    init {
        launch {
            for ((identifier, type, extra) in MessageSubscription.confirmChannel) {
                try {
                    logD(
                        "MsgFlow",
                        "[send-socket] confirm identifier=$identifier type=$type waiting=${waitingAckQueue.size}"
                    )
                    when (type) {
                        ConfirmType.SUCCESS -> {
                            val data = extra?.getByteArray("data") ?: continue
                            val reply = parseReplay(data)
                            sendConfirm(identifier, reply.logId, reply.datetime)
                        }
                        ConfirmType.FAIL -> sendFail(identifier)
                        ConfirmType.ABORT -> sendAbort(identifier)
                    }
                } catch (e: Exception) {
                    logE("SocketSender", e.message)
                }
            }
        }
    }

    suspend fun sendMessage(url: String, message: ChatMessage): Boolean {
        return enqueueMessage(url, message) { _, _ ->  }
    }

    private suspend fun enqueueMessage(
        url: String,
        message: ChatMessage,
        callback: suspend (Boolean, ChatMessage) -> Unit
    ): Boolean {
        val result = manager.send(url, message.buildByteArray(), extra(Socket.Op.SendMsg, requireAck = true))
        if (result != null) {
            // 重新发送时清除上一次的失败原因
            message.failReason = null
            message.failDetail = null
            waitingAckQueue.add(SendingMessage(result, message, callback))
            logD(
                "MsgFlow",
                "[send-socket] enqueue msgId=${message.msgId} identifier=$result " +
                    "channel=${message.channelType} type=${message.msgType} waiting=${waitingAckQueue.size}"
            )
            return true
        }
        logD(
            "MsgFlow",
            "[send-socket] socket unavailable msgId=${message.msgId} channel=${message.channelType}"
        )
        return false
    }

    /**
     * 聊天消息转换为字节数组
     */
    private suspend fun ChatMessage.buildByteArray(): ByteArray {
        val body = if (ChatConfig.ENCRYPT) {
            if (channelType == ChatConst.PRIVATE_CHANNEL) {
                val target = contactManager.getUserInfo(contact, true)
                buildProto(target.getPubKey(), delegate.preference.PRI_KEY)
            } else {
                val target = contactManager.getGroupInfo(contact)
                buildProto(target.key)
            }
        } else {
            buildProto()
        }
        return body.toByteArray()
    }

    /**
     * 移除waitingAckQueue中待确认的消息，将消息状态改为发送成功
     */
    private suspend fun sendConfirm(queueIdentify: String, logId: Long, datetime: Long) {
        val itr = waitingAckQueue.iterator()
        while (itr.hasNext()) {
            val item = itr.next()
            if (item.identifier == queueIdentify) {
                val state = if (MessageSubscription.ackSet.remove(logId)) {
                    MsgState.SENT_AND_RECEIVE
                } else {
                    MsgState.SENT
                }
                item.message.failReason = null
                item.message.failDetail = null
                updateMessageState(item, logId, datetime, state)
                item.callback(true, item.message)
                logD(
                    "MsgFlow",
                    "[send-socket] ack msgId=${item.message.msgId} logId=$logId state=$state"
                )
                break
            }
        }
    }

    /**
     * 移除waitingAckQueue中待确认的消息，将消息状态改为发送失败
     */
    private suspend fun sendFail(identifier: String) {
        val itr = waitingAckQueue.iterator()
        while (itr.hasNext()) {
            val item = itr.next()
            if (item.identifier == identifier) {
                // 重试多次仍未收到服务器确认，按超时处理
                item.message.failReason = SendFailReason.TIMEOUT
                item.message.failDetail = "no ack after retry (identifier=$identifier)"
                updateMessageState(item, item.message.logId, item.message.datetime, MsgState.FAIL)
                item.callback(false, item.message)
                itr.remove()
                logE(
                    "MsgFlow",
                    "[send-socket] fail msgId=${item.message.msgId} identifier=$identifier " +
                        "reason=${item.message.failReason} detail=${item.message.failDetail}"
                )
                MessageSubscription.onSendFail(item.message)
                break
            }
        }
    }

    /**
     * 发送终止，清除waitingAckQueue中待确认的消息
     */
    private suspend fun sendAbort(identifier: String) {
        val itr = waitingAckQueue.iterator()
        while (itr.hasNext()) {
            val item = itr.next()
            if (item.identifier == identifier) {
                // 连接中断导致发送被终止
                item.message.failReason = SendFailReason.CONNECT_FAILED
                item.message.failDetail = "send aborted, connection may be closed (identifier=$identifier)"
                updateMessageState(item, item.message.logId, item.message.datetime, MsgState.FAIL)
                item.callback(false, item.message)
                itr.remove()
                logE(
                    "MsgFlow",
                    "[send-socket] abort msgId=${item.message.msgId} identifier=$identifier " +
                        "reason=${item.message.failReason} detail=${item.message.failDetail}"
                )
                MessageSubscription.onSendFail(item.message)
                break
            }
        }
    }

    private suspend fun updateMessageState(sending: SendingMessage, logId: Long, datetime: Long, state: Int) {
        sending.message.let { message->
            message.logId = logId
            message.datetime = datetime
            message.state = state
            // 数据库操作
            database.messageDao().updateMessage(message.msgId, message.logId, message.datetime, message.state)
            database.recentSessionDao().updateRecentLogId(message.logId, message.contact, message.channelType)
        }
        logD(
            "MsgFlow",
            "[send-socket] state msgId=${sending.message.msgId} logId=$logId state=$state " +
                "fail=${sending.message.failReason} detail=${sending.message.failDetail}"
        )
        MessageSubscription.onUpdateState(sending.message)
    }

    private suspend fun parseReplay(bytes: ByteArray): Biz.SendMsgAck {
        return suspendCancellableCoroutine { continuation ->
            try {
                val msg = Api.Proto.parseFrom(bytes)
                continuation.resume(Biz.SendMsgAck.parseFrom(msg.body))
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    override fun dispose() {
        waitingAckQueue.clear()
    }

    inner class SendingMessage(
        /**
         * 消息队列唯一标识符
         */
        val identifier: String,
        val message: ChatMessage,
        val callback: suspend (Boolean, ChatMessage) -> Unit
    ) : Serializable

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.Main.immediate
}