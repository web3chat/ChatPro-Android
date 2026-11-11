package com.fzm.chat.square

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.R
import com.fzm.chat.biz.utils.defaultAvatar
import com.fzm.chat.biz.widget.ChatAvatarView
import com.fzm.chat.core.data.ChatConst
import com.zjy.architecture.ext.load

/**
 * 广场帖子列表适配器
 *
 * @param isMine 是否本人发布（决定是否展示删除入口）
 */
class SquarePostAdapter(
    private val isMine: (SquarePost) -> Boolean
) : BaseQuickAdapter<SquarePost, BaseViewHolder>(
    R.layout.item_square_post,
    mutableListOf()
) {

    companion object {
        /** 局部刷新：仅点赞状态 */
        const val PAYLOAD_LIKE = "payload_like"

        /** 局部刷新：仅头像 */
        const val PAYLOAD_AVATAR = "payload_avatar"

        /** 列表正文最多展示字数，超出截断（点击进详情看全文） */
        private const val MAX_CONTENT = 100
    }

    var onLikeClick: ((SquarePost) -> Unit)? = null
    var onCommentClick: ((SquarePost) -> Unit)? = null
    var onDeleteClick: ((SquarePost) -> Unit)? = null

    /** 编辑自己的帖（为空则不展示编辑入口；仅可编辑时限内 + 广场未限制发帖） */
    var onEditClick: ((SquarePost) -> Unit)? = null

    /** 举报（他人的帖子，自己发的帖显示删除） */
    var onReportClick: ((SquarePost) -> Unit)? = null

    /** 是否展示「举报」入口（广场列表置 false；用户主页 / 详情页保持 true），需在绑定数据前设置 */
    var showReport = true

    /** 点击头像或昵称（查看该用户的动态），为空则不可点 */
    var onAuthorClick: ((SquarePost) -> Unit)? = null

    /** 点击分享（分享到微信等外部 App） */
    var onShareClick: ((SquarePost) -> Unit)? = null

    /**
     * 点击正文
     *
     * 正文可长按选择复制（textIsSelectable），TextView 会自己消费点击事件，
     * 所以必须单独给正文设一个点击回调（进详情），否则点文字没反应
     */
    var onContentClick: ((SquarePost) -> Unit)? = null

    private val normalColor = Color.parseColor("#8A97A5")
    private val likedColor = Color.parseColor("#DD5F5F")

    override fun convert(holder: BaseViewHolder, item: SquarePost) {
        val context = holder.itemView.context
        // 作者昵称：公众号更显眼（品牌蓝 + 字号大一号）
        holder.getView<TextView>(R.id.tv_author).bindSquareAuthor(item.displayName, item.isOfficial)
        // 身份角标：运营台官方号（金色「官方号」）/ 普通公众号（蓝色「公众号」）
        holder.getView<TextView>(R.id.tv_official_tag)
            .bindSquareOfficialTag(item.isOfficial, item.isPlatformOfficial)
        // 运营置顶
        holder.setGone(R.id.tv_pinned, !item.isPinned)
        // 金标公众号（昵称旁金色徽标）
        holder.setGone(R.id.iv_gold, !item.isGold)
        // 官方帖标签（公告 / 消息 / 活动，服务端中文值 → 本地文案）
        val tag = item.postTag
        holder.setGone(R.id.tv_tag, tag.isBlank())
        if (tag.isNotBlank()) holder.setText(R.id.tv_tag, context.squareTagLabel(tag))
        holder.setText(R.id.tv_time, SquareTime.display(context, item.createdAt))
        // 正文：Markdown 帖直接在列表内渲染富文本（保留标题 / 加粗 / 列表等样式），纯文本帖原样展示
        holder.getView<TextView>(R.id.tv_content).text = displayContent(item)
        holder.setText(R.id.tv_views, item.viewNum.toString())
        holder.setText(R.id.tv_comment_count, item.commentNum.toString())

        // 审核状态（仅作者自己能看到未过审的帖子）
        when {
            item.isAuditing -> {
                holder.setVisible(R.id.tv_status, true)
                holder.setText(R.id.tv_status, context.getString(R.string.square_status_auditing))
            }

            item.isRejected -> {
                holder.setVisible(R.id.tv_status, true)
                holder.setText(R.id.tv_status, context.getString(R.string.square_status_rejected))
            }

            else -> holder.setGone(R.id.tv_status, true)
        }

        bindAvatar(holder, item)
        bindAuthorClick(holder, item)
        bindLike(holder, item)

        // 正文可长按选文字，普通点击经常不响应 → 手动识别轻点，进详情看全文
        holder.getView<TextView>(R.id.tv_content).bindSelectableTap { onContentClick?.invoke(item) }

        val mine = isMine(item)
        holder.setGone(R.id.tv_delete, !mine)
        // 他人的帖子展示举报入口（自己的帖展示删除）；广场列表 showReport=false 不展示
        holder.setGone(R.id.tv_report, mine || !showReport)
        // 编辑入口：仅自己的帖 + 在可编辑时限内 + 广场未限制发帖（文档增量：置顶与二次编辑）
        val canEdit = onEditClick != null && mine && SquareGate.canPost && item.editable()
        holder.setGone(R.id.tv_edit, !canEdit)
        holder.getView<View>(R.id.tv_edit).setOnClickListener { onEditClick?.invoke(item) }

        SquareImageGrid.bind(holder.getView(R.id.ll_images), item.images)

        holder.getView<LinearLayout>(R.id.ll_like).setOnClickListener { onLikeClick?.invoke(item) }
        holder.getView<LinearLayout>(R.id.ll_comment).setOnClickListener { onCommentClick?.invoke(item) }
        holder.getView<View>(R.id.tv_delete).setOnClickListener { onDeleteClick?.invoke(item) }
        holder.getView<View>(R.id.tv_report).setOnClickListener { onReportClick?.invoke(item) }
        holder.getView<LinearLayout>(R.id.ll_share).setOnClickListener { onShareClick?.invoke(item) }
    }

    /**
     * 局部刷新：只更新指定内容，避免整项重绑导致图片重新加载闪烁
     */
    override fun convert(holder: BaseViewHolder, item: SquarePost, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            convert(holder, item)
            return
        }
        val bundle = payloads[0] as? Bundle ?: return
        if (bundle.getBoolean(PAYLOAD_LIKE, false)) {
            bindLike(holder, item)
        }
        if (bundle.getBoolean(PAYLOAD_AVATAR, false)) {
            bindAvatar(holder, item)
        }
    }

    private fun bindAvatar(holder: BaseViewHolder, item: SquarePost) {
        val avatar = holder.getView<ChatAvatarView>(R.id.iv_avatar)
        avatar.load(SquareAvatarCache.getForPost(item), defaultAvatar(ChatConst.PRIVATE_CHANNEL))
    }

    /**
     * 点击头像或昵称 → 同一行为（查看该用户的动态）
     */
    private fun bindAuthorClick(holder: BaseViewHolder, item: SquarePost) {
        val click = onAuthorClick
        val listener = if (click == null) null else View.OnClickListener { click(item) }
        holder.getView<View>(R.id.iv_avatar).setOnClickListener(listener)
        holder.getView<View>(R.id.tv_author).setOnClickListener(listener)
    }

    /**
     * 列表正文最多显示 [MAX_CONTENT] 个字，超出部分加省略号（详情页展示全文）
     *
     * Markdown 帖（`contentFormat=markdown`）：列表内直接渲染为富文本，样式（标题 / 加粗 / 列表 / 引用等）可见；
     * 源文过长时先按源文长度截断再加省略号，再交给渲染器（未闭合的标记按原文展示，不会吞掉用户内容）
     */
    private fun displayContent(item: SquarePost): CharSequence {
        val source = item.content.orEmpty()
        val truncated = if (source.length <= MAX_CONTENT) source else source.take(MAX_CONTENT) + "…"
        return if (item.isMarkdown) SquareMarkdown.render(truncated) else truncated
    }

    private fun bindLike(holder: BaseViewHolder, item: SquarePost) {
        val color = if (item.isLiked) likedColor else normalColor
        val likeIcon = holder.getView<ImageView>(R.id.iv_like)
        likeIcon.setImageResource(
            if (item.isLiked) R.drawable.square_ic_liked else R.drawable.square_ic_like
        )
        likeIcon.setColorFilter(color)
        holder.setText(R.id.tv_like_count, item.likeNum.toString())
        holder.setTextColor(R.id.tv_like_count, color)
    }
}
