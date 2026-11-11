package com.fzm.chat.utils

import android.content.Context
import com.fzm.chat.R
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.msg.SendFailReason
import dtalk.biz.Biz
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将消息发送失败原因转换为可展示的文案
 *
 * @author zhengjy
 * @since 2026/10/19
 */
fun ChatMessage.sendFailText(context: Context): String {
    val detail = failDetail?.trim()?.takeIf { it.isNotEmpty() }
    return when (failReason) {
        SendFailReason.NETWORK_UNAVAILABLE ->
            context.getString(R.string.chat_send_fail_network)
        SendFailReason.TIMEOUT ->
            context.getString(R.string.chat_send_fail_timeout)
        SendFailReason.CONNECT_FAILED ->
            context.getString(R.string.chat_send_fail_connect)
        SendFailReason.UPLOAD_FAILED ->
            context.getString(R.string.chat_send_fail_upload)
        SendFailReason.SERVER_ERROR ->
            if (detail != null) {
                context.getString(R.string.chat_send_fail_server, detail)
            } else {
                context.getString(R.string.chat_send_fail_server_empty)
            }
        else -> context.getString(R.string.basic_error_send)
    }
}

/**
 * 构建发送失败的详细错误日志（展示给用户，便于定位问题）
 *
 * 前半部分是本地化的失败摘要，后半部分是便于排查的技术信息。
 */
fun ChatMessage.sendFailDetailText(context: Context, server: String? = null): String {
    val detail = failDetail?.trim()?.takeIf { it.isNotEmpty() }
    val time = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(datetime))
    } catch (e: Exception) {
        datetime.toString()
    }
    val channel = if (channelType == ChatConst.PRIVATE_CHANNEL) {
        "private($channelType)"
    } else {
        "group($channelType)"
    }
    val type = Biz.MsgType.forNumber(msgType)?.name ?: msgType.toString()
    val detailText = detail?.let {
        if (it.length > 600) it.take(600) + "..." else it
    } ?: "-"
    return buildString {
        append(sendFailText(context))
        append("\n\n")
        append("msgId: ").append(msgId)
        append("\nlogId: ").append(logId)
        append("\nchannel: ").append(channel)
        append("\nfrom: ").append(from)
        append("\ntarget: ").append(target)
        append("\nmsgType: ").append(type)
        append("\nstate: ").append(state)
        append("\nreason: ").append(failReason?.name ?: "UNKNOWN")
        append("\ndetail: ").append(detailText)
        if (!server.isNullOrEmpty()) {
            append("\nserver: ").append(server)
        }
        append("\ntime: ").append(time)
    }
}
