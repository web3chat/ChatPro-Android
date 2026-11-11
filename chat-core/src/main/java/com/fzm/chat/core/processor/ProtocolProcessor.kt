package com.fzm.chat.core.processor

import com.fzm.arch.connection.processor.Processor
import com.google.protobuf.ByteString
import com.zjy.architecture.util.logD
import com.zjy.architecture.util.logE
import com.zjy.architecture.util.logV
import dtalk.biz.Biz
import dtalk.biz.signal.Signaling
import dtalk.proto.Api
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * @author zhengjy
 * @since 2021/03/11
 * Description:
 */
class ProtocolProcessor(
    private val msgProcessors: List<Processor<Biz.Message>>,
    private val signalingProcessors: List<Processor<Signaling.Msg>>,
) : Processor<Api.Proto> {

    override suspend fun process(server: String, message: Api.Proto): Api.Proto? {
        val event = Biz.Event.forNumber(message.event)
        logV(
            "MsgFlow",
            "[recv] proto server=$server event=$event(${message.event}) bodySize=${message.body.size()}"
        )
        when (event) {
            Biz.Event.message -> {
                try {
                    val msg = parseMessage(message.body)
                    logD(
                        "MsgFlow",
                        "[recv] message msgId=${msg.msgId} logId=${msg.logId} channel=${msg.channelType} " +
                            "from=${msg.from} target=${msg.target} type=${msg.msgType}"
                    )
                    msgProcessors.process(server, msg)
                } catch (e: Exception) {
                    logE("MsgFlow", "[recv] parse message failed: ${e.message}")
                }
                return null
            }
            Biz.Event.messageReply -> {
                // 已经在MessageDispatcher中的onReadAck处理
            }
            Biz.Event.signaling -> {
                try {
                    val msg = parseNotifyMessage(message.body)
                    logD(
                        "MsgFlow",
                        "[recv] signaling server=$server action=${msg.action} sid=${msg.sid} " +
                            "reliable=${msg.reliable} bodySize=${message.body.size()}"
                    )
                    signalingProcessors.process(server, msg)
                } catch (e: Exception) {
                    logE("MsgFlow", "[recv] parse signaling failed: ${e.message}")
                }
                return null
            }
            else -> {

            }
        }
        return message
    }

    private suspend fun parseMessage(bytes: ByteString): Biz.Message {
        return suspendCancellableCoroutine { continuation ->
            try {
                continuation.resume(Biz.Message.parseFrom(bytes))
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    private suspend fun parseNotifyMessage(bytes: ByteString): Signaling.Msg {
        return suspendCancellableCoroutine { continuation ->
            try {
                continuation.resume(Signaling.Msg.parseFrom(bytes))
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    /**
     * 传递给每一个processor进行处理
     */
    private suspend fun <T> List<Processor<T>>.process(server: String, init: T) {
        var msg: T? = init
        for (processor in this) {
            if (msg == null) {
                break
            } else {
                msg = processor.process(server, msg)
            }
        }
    }
}