package com.fzm.chat.core.data.po

import com.fzm.chat.core.data.model.ChatMessage
import java.io.Serializable

/**
 * @author zhengjy
 * @since 2021/06/17
 * Description: 转发来源与阅后即焚共用 Source。from/target 必须可空，服务端只带 burn 时二者为 null。
 */
data class MessageSource(
    val channelType: Int,
    val from: SourceUser?,
    val target: SourceUser?,
    val burn: Boolean = false
) : Serializable, Cloneable {

    companion object {
        fun create(message: ChatMessage, fromName: String, targetName: String): MessageSource {
            return create(message.channelType, message.from, fromName, message.target, targetName)
        }

        fun create(
            channelType: Int,
            from: String,
            fromName: String,
            target: String,
            targetName: String
        ): MessageSource {
            return MessageSource(
                channelType,
                SourceUser(from, fromName),
                SourceUser(target, targetName)
            )
        }

        /**
         * 纯阅后即焚：只设 burn=true，不填转发来源 from/target
         */
        fun burnOnly(channelType: Int): MessageSource {
            return MessageSource(channelType, null, null, burn = true)
        }
    }

    public override fun clone(): MessageSource {
        return MessageSource(
            channelType,
            from?.let { SourceUser(it.id, it.name) },
            target?.let { SourceUser(it.id, it.name) },
            burn
        )
    }
}

data class SourceUser(
    val id: String,
    val name: String
) : Serializable