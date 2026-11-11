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
 * 关注 / 粉丝列表适配器
 *
 * 行内右侧「关注 / 已关注」按钮状态取自 [SquareFollowCache]（帖子与列表接口都不返回 followed）。
 */
class SquareFollowAdapter : BaseQuickAdapter<SquareFollowUser, BaseViewHolder>(
    R.layout.item_square_follow,
    mutableListOf()
) {

    init {
        addChildClickViewIds(R.id.tv_follow)
    }

    override fun convert(holder: BaseViewHolder, item: SquareFollowUser) {
        val context = holder.itemView.context
        val nameView = holder.getView<TextView>(R.id.tv_name)
        nameView.bindSquareAuthor(item.displayName, item.isOfficial, baseSizeSp = 15f)
        // 身份角标：运营台官方账号（square-official）展示金色「官方号」
        val tagView = holder.getView<TextView>(R.id.tv_official_tag)
        tagView.bindSquareOfficialTag(item.isOfficial, isPlatformOfficialAddress(item.address))
        // 角标优先：长昵称先省略号，保证「公众号 / 官方号」完整显示
        nameView.limitSquareNameWidth(holder.getView(R.id.ll_name_row), tagView)
        holder.setText(R.id.tv_time, SquareTime.display(context, item.followedAt))
        // 服务端返回的公众号头像写入缓存：用户主页头部等按地址取的场景直接用
        if (item.isOfficial && !item.avatar.isNullOrBlank()) {
            SquareAvatarCache.putOfficial(item.addr, item.avatar.orEmpty())
        }
        holder.getView<ChatAvatarView>(R.id.iv_avatar).load(
            SquareAvatarCache.getForAddress(item.addr, item.isOfficial),
            defaultAvatar(ChatConst.PRIVATE_CHANNEL)
        )
        bindFollowButton(holder.getView(R.id.tv_follow), SquareFollowCache.isFollowed(item.addr))
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
