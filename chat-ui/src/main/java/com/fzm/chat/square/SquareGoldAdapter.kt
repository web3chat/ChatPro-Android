package com.fzm.chat.square

import android.widget.TextView
import androidx.core.content.ContextCompat
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.R
import com.fzm.chat.biz.utils.defaultAvatar
import com.fzm.chat.biz.widget.ChatAvatarView
import com.fzm.chat.core.data.ChatConst
import com.zjy.architecture.ext.load

/**
 * 金标公众号墙适配器（GET /square/app/officials）
 *
 * - 关注按钮状态用接口返回的 `followed`（服务端权威值，文档 §3）
 * - 自己的号不展示关注按钮（不能关注自己 -20411）
 *
 * @param isMe 是否为当前登录地址（过滤自己的号）
 */
class SquareGoldAdapter(
    private val isMe: (String) -> Boolean = { false }
) : BaseQuickAdapter<SquareOfficialCard, BaseViewHolder>(
    R.layout.item_square_gold,
    mutableListOf()
) {

    init {
        addChildClickViewIds(R.id.tv_follow)
    }

    override fun convert(holder: BaseViewHolder, item: SquareOfficialCard) {
        val context = holder.itemView.context
        holder.getView<TextView>(R.id.tv_name)
            .bindSquareAuthor(item.displayName, official = true, baseSizeSp = 15f)
        holder.setGone(R.id.iv_gold, !item.isGold)
        val intro = item.intro.orEmpty()
        holder.setGone(R.id.tv_intro, intro.isBlank())
        if (intro.isNotBlank()) holder.setText(R.id.tv_intro, intro)
        holder.setText(
            R.id.tv_stat,
            context.getString(R.string.square_gold_stat, item.followers, item.posts)
        )
        // 服务端返回的公众号头像写入缓存：用户主页头部等按地址取的场景直接用
        if (!item.avatar.isNullOrBlank()) {
            SquareAvatarCache.putOfficial(item.addr, item.avatar.orEmpty())
        }
        holder.getView<ChatAvatarView>(R.id.iv_avatar).load(
            SquareAvatarCache.getForAddress(item.addr, true),
            defaultAvatar(ChatConst.PRIVATE_CHANNEL)
        )
        val button = holder.getView<TextView>(R.id.tv_follow)
        holder.setGone(R.id.tv_follow, isMe(item.addr))
        bindFollowButton(button, item.isFollowed)
    }

    private fun bindFollowButton(button: TextView, followed: Boolean) {
        button.setText(if (followed) R.string.square_followed else R.string.square_follow)
        button.setBackgroundResource(
            if (followed) R.drawable.shape_square_followed_btn
            else R.drawable.shape_square_follow_btn
        )
        button.setTextColor(
            ContextCompat.getColor(
                button.context,
                if (followed) R.color.color_8E92A3 else R.color.white
            )
        )
    }
}
