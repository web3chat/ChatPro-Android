package com.fzm.chat.core.processor

import android.content.Context
import chat33.comet.Socket
import com.alibaba.android.arouter.facade.annotation.Route
import com.fzm.arch.connection.utils.ProtocolPrinter
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.router.core.CoreModule
import com.google.protobuf.ByteString
import dtalk.biz.Biz
import dtalk.biz.msg.Msg
import dtalk.biz.signal.Signaling
import dtalk.proto.Api

/**
 * @author zhengjy
 * @since 2022/07/28
 * Description:
 */
@Route(path = CoreModule.PRINTER)
class ProtocolPrinterImpl : ProtocolPrinter {

    /**
     * 是否显示消息版本号（修改后可能影响日志对齐，需要重新对齐）
     */
    private val showVer = false

    override fun print(proto: Socket.Proto): String {
        return with(proto) {
            try {
                when (op) {
                    Socket.Op.Auth_VALUE -> {
                        "${version(ver)}op=${Socket.Op.forNumber(op)}\t\tseq=$seq\t${Socket.AuthMsg.parseFrom(body).printMessage()}"
                    }
                    Socket.Op.AuthReply_VALUE -> {
                        "${version(ver)}op=${Socket.Op.forNumber(op)}\tseq=$seq"
                    }
                    Socket.Op.Heartbeat_VALUE -> {
                        "${version(ver)}op=${Socket.Op.forNumber(op)}\t\tseq=$seq"
                    }
                    Socket.Op.HeartbeatReply_VALUE -> {
                        "${version(ver)}op=${Socket.Op.forNumber(op)}\tseq=$seq"
                    }
                    Socket.Op.SendMsg_VALUE -> {
                        "${version(ver)}op=${Socket.Op.forNumber(op)}\t\t\tseq=$seq\t${Api.Proto.parseFrom(body).printMessage()}"
                    }
                    Socket.Op.SendMsgReply_VALUE->{
                        "${version(ver)}op=${Socket.Op.forNumber(op)}\tseq=$seq\t${Api.Proto.parseFrom(body).printMessage()}"
                    }
                    Socket.Op.ReceiveMsg_VALUE -> {
                        "${version(ver)}op=${Socket.Op.forNumber(op)}\t\tseq=$seq\t${Api.Proto.parseFrom(body).printMessage()}"
                    }
                    Socket.Op.ReceiveMsgReply_VALUE -> {
                        "${version(ver)}op=${Socket.Op.forNumber(op)}\tseq=$seq\tack=$ack"
                    }
                    else -> "${version(ver)}op=${Socket.Op.forNumber(op)}\t\tseq=$seq\tack=$ack"
                }
            } catch (e: Exception) {
                "${version(ver)}op=${Socket.Op.forNumber(op)} seq=$seq body=$body"
            }
        }
    }

    private fun version(version: Int): String {
        return if (showVer) "ver=$version\t" else ""
    }

    override fun init(context: Context?) {

    }
}

fun Api.Proto.printMessage(): String {
    val sb = StringBuilder()
    sb.append("event=${Biz.Event.forNumber(event)}, ")
    when (event) {
        Biz.Event.message_VALUE -> {
            val message = Biz.Message.parseFrom(body)
            sb.append("body=${message.printMessage()}")
        }
        Biz.Event.messageReply_VALUE -> {
            val reply = Biz.SendMsgAck.parseFrom(body)
            sb.append("body=[logId=${reply.logId}, datetime=${reply.datetime}]")
        }
        Biz.Event.signaling_VALUE -> {
            val signaling = Signaling.Msg.parseFrom(body)
            sb.append("action=${Signaling.ActionType.forNumber(signaling.action)}")
        }
        else-> sb.append("body=$body")
    }
    return sb.toString()
}

private fun Biz.Message.printMessage(): String {
    val sb = StringBuilder()
    sb.append("[")
    sb.append("from=${from}, ")
    sb.append("to=${target}, ")
    sb.append("msgType=${msgType}, ")
    sb.append("logId=${logId}, ")
    sb.append("msg=${if (ChatConfig.ENCRYPT) msg else printMsgContent(msgType, msg)}, ")
    sb.append("]")
    return sb.toString()
}

internal fun printMsgContent(msgType: Biz.MsgType, bytes: ByteString): String {
    return when (msgType) {
        Biz.MsgType.System, Biz.MsgType.Text -> {
            val text = Msg.Text.parseFrom(bytes)
            "content=${text.content}, mention=${text.mentionList}"
        }
        Biz.MsgType.Audio -> {
            val audio = Msg.Audio.parseFrom(bytes)
            "url=${audio.url}, time=${audio.time}"
        }
        Biz.MsgType.Image -> {
            val image = Msg.Image.parseFrom(bytes)
            "url=${image.url}"
        }
        Biz.MsgType.Video -> {
            val video = Msg.Video.parseFrom(bytes)
            "url=${video.url}, time=${video.time}"
        }
        Biz.MsgType.File -> {
            val file = Msg.File.parseFrom(bytes)
            "url=${file.url}, filename=${file.name}, size=${file.size}, md5=${file.md5}"
        }
        Biz.MsgType.Notification -> {
            val notification = Msg.Notification.parseFrom(bytes)
            "notify=${notification.type}"
        }
        Biz.MsgType.Forward -> {
            val forward = Msg.Forward.parseFrom(bytes)
            "forwardSize=${forward.logsCount}"
        }
        Biz.MsgType.Transfer -> {
            val transfer = Msg.Transfer.parseFrom(bytes)
            "chain=${transfer.chain}, platform=${transfer.platform}, hash=${transfer.txHash}, coinName=${transfer.coinName}, coinType=${transfer.coinType}"
        }
        Biz.MsgType.RedPacket -> {
            val redPacket = Msg.RedPacket.parseFrom(bytes)
            "packetId=${redPacket.packetId}, exec=${redPacket.exec}, remark=${redPacket.remark}, coinName=${redPacket.coinName}, coinType=${redPacket.coinType}, packetType=${redPacket.packetType}, privateKey=${redPacket.privateKey}, expire=${redPacket.expire}"
        }
        Biz.MsgType.ContactCard -> {
            val card = Msg.ContactCard.parseFrom(bytes)
            "id=${card.id}, name=${card.name}, avatar=${card.avatar}, server=${card.server}, inviter=${card.inviter}"
        }
        else -> "unknown message"
    }
}

private fun Socket.AuthMsg.printMessage(): String {
    val sb = StringBuilder()
    sb.append("appId=$appId, ")
    sb.append("token=$token, ")
    if (ext.size() > 0) {
        val ext = Biz.AuthExt.parseFrom(ext)
        sb.append("ext=${ext.printMessage()}")
    } else {
        sb.append("ext=[]")
    }
    return sb.toString()
}

private fun Biz.AuthExt.printMessage(): String {
    val sb = StringBuilder()
    sb.append("[")
    sb.append("connType=${connType}, ")
    sb.append("deviceName=${deviceName}, ")
    sb.append("nickname=${nickname}, ")
    sb.append("device=${device}, ")
    sb.append("deviceToken=${deviceToken}, ")
    sb.append("uuid=${uuid}")
    sb.append("]")
    return sb.toString()
}