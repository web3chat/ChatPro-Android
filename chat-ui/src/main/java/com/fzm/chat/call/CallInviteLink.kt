package com.fzm.chat.call

import android.net.Uri
import com.fzm.chat.core.data.po.MessageContent

/**
 * 通话房间链接解析
 *
 * 语音聊天邀请已改为「普通文本 + 房间链接」发送（服务端对 RTCCall 类型消息不回 ack，
 * 会导致消息一直停留在「发送中」）。文本里的链接被点击时通过本工具识别，
 * 交给 [CallLauncher] 进入通话页（注入 H5Wallet 并追加当前用户 name）。
 *
 * 链接格式：{CALL_SERVER}/?room=h5_voice_xxx&pin=7514&mode=voice#/
 */
object CallInviteLink {

    /**
     * 解析通话房间链接；非通话链接返回 null
     */
    fun parse(url: String?): MessageContent? {
        if (url.isNullOrEmpty()) return null
        return try {
            val uri = Uri.parse(url)
            if (!uri.host.equals(CallConfig.host(), ignoreCase = true)) return null
            val room = uri.getQueryParameter("room")?.takeIf { it.isNotEmpty() } ?: return null
            val pin = uri.getQueryParameter("pin").orEmpty()
            val mode = uri.getQueryParameter("mode")?.takeIf { it.isNotEmpty() } ?: "voice"
            MessageContent.callInvite(
                room,
                pin,
                mode,
                CallConfig.roomUrl(room, pin, mode)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 判断是否为通话房间链接
     */
    fun isCallLink(url: String?): Boolean = parse(url) != null
}
