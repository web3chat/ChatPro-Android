package com.fzm.chat.square

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.R
import com.fzm.chat.biz.utils.defaultAvatar
import com.fzm.chat.biz.widget.ChatAvatarView
import com.fzm.chat.core.data.ChatConst
import com.zjy.architecture.ext.load

/**
 * 广场收件箱适配器（GET /square/app/replies）
 *
 * 行展示「谁 + 评论了你的动态 / 回复了你 + 帖子标题 + 评论内容」；
 * 未读打小红点，`available=false`（帖子已删/评论不可见）整体降透明度并置灰正文。
 */
class SquareInboxAdapter : BaseQuickAdapter<SquareReplyItem, BaseViewHolder>(
    R.layout.item_square_inbox,
    mutableListOf()
) {

    override fun convert(holder: BaseViewHolder, item: SquareReplyItem) {
        val context = holder.itemView.context
        holder.setText(R.id.tv_name, item.displayName)
        holder.setText(
            R.id.tv_action,
            if (item.isPostComment) R.string.square_inbox_floor else R.string.square_inbox_reply
        )
        holder.setText(R.id.tv_time, SquareTime.display(context, item.createdAt))
        holder.setText(R.id.tv_title, item.title)
        val contentView = holder.getView<TextView>(R.id.tv_content)
        val hasContent = !item.content.isNullOrBlank()
        contentView.text = if (hasContent) {
            item.content
        } else {
            context.getString(R.string.square_inbox_unavailable)
        }
        contentView.setTextColor(
            ContextCompat.getColor(
                context,
                if (hasContent) R.color.gray_33 else R.color.color_8E92A3
            )
        )
        holder.getView<View>(R.id.v_unread).isVisible = !item.isRead
        holder.itemView.alpha = if (item.isAvailable) 1f else 0.6f
        holder.getView<ChatAvatarView>(R.id.iv_avatar).load(
            SquareAvatarCache.getForAddress(item.authorAddr, false),
            defaultAvatar(ChatConst.PRIVATE_CHANNEL)
        )
    }
}
