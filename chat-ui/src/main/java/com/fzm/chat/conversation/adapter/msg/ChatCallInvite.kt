package com.fzm.chat.conversation.adapter.msg

import android.view.View
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.R
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.rtc.calling.RTCCalling
import com.fzm.chat.databinding.LayoutMsgCallInviteBinding

/**
 * @author zhengjy
 * Description: 通话房间邀请卡片（RTCCall 消息携带房间链接时展示）
 */
class ChatCallInvite(listener: ChatMessageClickListener) : ChatBaseItem(listener) {

    override val itemViewType: Int = ChatConst.CALL_INVITE_TYPE

    override fun setupView(holder: BaseViewHolder, item: ChatMessage) {
        val binding = getOrCreateBinding<LayoutMsgCallInviteBinding>(holder)
        val voice = item.msg.rtcType == RTCCalling.TYPE_AUDIO_CALL
        binding.ivCallIcon.setImageResource(
            if (voice) R.mipmap.icon_chat_call_voice else R.mipmap.icon_chat_call_video
        )
        binding.tvCallTitle.text = context.getString(
            if (voice) R.string.chat_call_invite_title_voice else R.string.chat_call_invite_title_video
        )
        binding.tvCallDesc.text = context.getString(
            R.string.chat_call_invite_desc, item.msg.callRoom ?: ""
        )
    }

    override fun chatLayout(holder: BaseViewHolder): View? {
        return getOrCreateBinding<LayoutMsgCallInviteBinding>(holder).root
    }
}
