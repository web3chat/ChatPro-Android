package com.fzm.chat.square

import android.os.Bundle
import android.view.View
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.R
import com.fzm.chat.biz.utils.defaultAvatar
import com.fzm.chat.biz.widget.ChatAvatarView
import com.fzm.chat.core.data.ChatConst
import com.zjy.architecture.ext.load

/**
 * 广场评论列表适配器
 *
 * @param isMine     是否本人发布（决定是否展示删除入口）
 * @param isOfficial 该评论作者是否为公众号（决定头像取值优先级）
 */
class SquareCommentAdapter(
    private val isMine: (SquareComment) -> Boolean,
    private val isOfficial: (SquareComment) -> Boolean = { false }
) : BaseQuickAdapter<SquareComment, BaseViewHolder>(
    R.layout.item_square_comment,
    mutableListOf()
) {

    companion object {
        /** 局部刷新：仅头像 */
        const val PAYLOAD_AVATAR = "payload_avatar"
    }

    var onDeleteClick: ((SquareComment) -> Unit)? = null

    /** 点击头像或昵称（查看该用户的动态），为空则不可点 */
    var onAuthorClick: ((SquareComment) -> Unit)? = null

    override fun convert(holder: BaseViewHolder, item: SquareComment) {
        holder.setText(R.id.tv_name, item.displayName)
        holder.setText(R.id.tv_time, SquareTime.display(holder.itemView.context, item.createdAt))
        holder.setText(R.id.tv_content, item.content)
        holder.setGone(R.id.tv_delete, !isMine(item))
        holder.getView<View>(R.id.tv_delete).setOnClickListener { onDeleteClick?.invoke(item) }
        bindAvatar(holder, item)
        bindAuthorClick(holder, item)
    }

    /**
     * 局部刷新：只更新指定内容（头像异步解析完成后刷新用）
     */
    override fun convert(holder: BaseViewHolder, item: SquareComment, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            convert(holder, item)
            return
        }
        val bundle = payloads[0] as? Bundle ?: return
        if (bundle.getBoolean(PAYLOAD_AVATAR, false)) {
            bindAvatar(holder, item)
        }
    }

    private fun bindAvatar(holder: BaseViewHolder, item: SquareComment) {
        val avatar = holder.getView<ChatAvatarView>(R.id.iv_avatar)
        avatar.load(
            SquareAvatarCache.getForAddress(item.authorAddr, isOfficial(item)),
            defaultAvatar(ChatConst.PRIVATE_CHANNEL)
        )
    }

    /**
     * 点击头像或昵称 → 同一行为（查看该用户的动态）
     */
    private fun bindAuthorClick(holder: BaseViewHolder, item: SquareComment) {
        val click = onAuthorClick
        val listener = if (click == null) null else View.OnClickListener { click(item) }
        holder.getView<View>(R.id.iv_avatar).setOnClickListener(listener)
        holder.getView<View>(R.id.tv_name).setOnClickListener(listener)
    }
}
