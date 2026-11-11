package com.fzm.chat.conversation.adapter.msg

import android.view.View
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.R
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.databinding.LayoutMsgBurnPlaceholderBinding

/**
 * 阅后即焚占位气泡：收方未阅读时展示，不展示正文，点击进独立详情页。
 */
class ChatBurnPlaceholder(listener: ChatMessageClickListener) : ChatBaseItem(listener) {

    override val itemViewType: Int = ChatConst.BURN_PLACEHOLDER_TYPE

    override fun setupView(holder: BaseViewHolder, item: ChatMessage) {
        val binding = getOrCreateBinding<LayoutMsgBurnPlaceholderBinding>(holder)
        binding.tvTips.text = context.getString(R.string.chat_burn_placeholder_tips)
    }

    override fun chatLayout(holder: BaseViewHolder): View? {
        return getOrCreateBinding<LayoutMsgBurnPlaceholderBinding>(holder).root
    }
}
