package com.fzm.chat.square

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.utils.defaultAvatar
import com.fzm.chat.biz.widget.ChatAvatarView
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.databinding.ActivitySquareDetailBinding
import com.fzm.widget.SwitchView
import com.fzm.widget.dialog.EasyDialog
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 广场动态详情：正文 + 评论 + 点赞 + 关闭评论（公众号）
 */
class SquareDetailActivity : BizActivity() {

    companion object {
        private const val EXTRA_POST_ID = "postId"
        private const val EXTRA_IS_OFFICIAL = "isOfficial"

        private const val PAGE_SIZE = 50

        /** 评论最多翻页数（防止异常情况下无限翻页） */
        private const val MAX_COMMENT_PAGE = 10

        /** 单楼展开回复时的拉取条数（回复通常不多，一次拉完） */
        private const val MAX_REPLY_SIZE = 100

        private val NORMAL_COLOR = Color.parseColor("#8A97A5")
        private val LIKED_COLOR = Color.parseColor("#DD5F5F")

        fun intent(context: Context, postId: Long, isOfficial: Boolean): Intent {
            return Intent(context, SquareDetailActivity::class.java)
                .putExtra(EXTRA_POST_ID, postId)
                .putExtra(EXTRA_IS_OFFICIAL, isOfficial)
        }
    }

    private val binding by init { ActivitySquareDetailBinding.inflate(layoutInflater) }

    private val delegate by inject<LoginDelegate>()

    private val contactManager by inject<ContactManager>()

    /** 当前楼层列表（thread=1：一级评论，每条带最多 2 条回复） */
    private val comments = mutableListOf<SquareComment>()

    /** 楼层视图（楼层 → item 视图），头像异步解析完成后按行刷新 */
    private val commentRows = mutableListOf<Pair<SquareComment, View>>()

    /** 已展开全部回复的楼 id */
    private val expandedFloors = mutableSetOf<Long>()

    /** 已按楼拉取到的全部回复（楼 id → 回复列表） */
    private val loadedReplies = mutableMapOf<Long, List<SquareComment>>()

    /** 含回复的可见评论数（服务端 commentCount；缺省回退楼层数） */
    private var commentTotal = 0

    /** 当前回复目标：点某条评论/回复后设置，发送时作为 replyToId */
    private var replyTarget: SquareComment? = null

    private var postId = 0L

    private var isOfficial = false

    private var post: SquarePost? = null

    private var commentsEnabled = true

    private var sending = false

    /** 我是否已关注当前帖作者（帖子对象不带 followed，用关注列表内存缓存维护） */
    private var followed = false

    /** 关注/取关请求进行中，避免连点 */
    private var following = false

    override val root: View
        get() = binding.root

    override fun initView() {
        postId = intent?.getLongExtra(EXTRA_POST_ID, 0L) ?: 0L
        isOfficial = intent?.getBooleanExtra(EXTRA_IS_OFFICIAL, false) ?: false
        if (postId == 0L) {
            finish()
            return
        }
    }

    override fun initData() {
        if (postId == 0L) return
        loadDetail()
        loadComments()
    }

    /** 从编辑页返回后重新拉详情（内容 / 置顶状态可能变化） */
    private var needReload = false

    override fun onResume() {
        super.onResume()
        if (needReload) {
            needReload = false
            loadDetail()
        }
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
        binding.ctbTitle.setOnRightClickListener {
            val item = post ?: return@setOnRightClickListener
            // 自己的帖：删除；他人的帖：举报
            if (isMine(item.authorAddr)) {
                confirmDeletePost(item)
            } else {
                SquareReportActivity.start(this, item)
            }
        }
        binding.btnSend.setOnClickListener { sendComment() }
        // 取消回复：回到「评论帖子」状态
        binding.tvReplyCancel.setOnClickListener { cancelReply() }
        binding.llLike.setOnClickListener { toggleLike() }
        // 分享到微信等外部 App（生成分享图片，失败退回文本）
        binding.llShare.setOnClickListener { post?.let { shareSquarePost(it, lifecycleScope) } }
        // 详情页直接关注作者（与用户主页同一套逻辑）
        binding.tvFollow.setOnClickListener { toggleFollow() }
        binding.svComment.setOnStateChangedListener(object : SwitchView.OnStateChangedListener {
            override fun toggleToOn(view: SwitchView?) {
                changeCommentsEnabled(view, true)
            }

            override fun toggleToOff(view: SwitchView?) {
                changeCommentsEnabled(view, false)
            }
        })
    }

    // ==================== 数据加载 ====================

    private fun loadDetail() {
        lifecycleScope.launch {
            val resp = SquareApi.detail(postId)
            if (resp.isSuccess && resp.data != null) {
                renderPost(resp.data!!)
            } else {
                toast(squareMessage(resp))
                finish()
            }
        }
    }

    private fun renderPost(item: SquarePost) {
        post = item
        // 服务端已返回公众号头像 → 同步缓存（用户主页头部等按地址取的场景可用）
        SquareAvatarCache.absorbPosts(listOf(item))
        commentsEnabled = item.canComment
        binding.tvAuthor.bindSquareAuthor(item.displayName, item.isOfficial)
        // 身份角标：运营台官方号（金色「官方号」）/ 普通公众号（蓝色「公众号」）
        binding.tvOfficialTag.bindSquareOfficialTag(item.isOfficial, item.isPlatformOfficial)
        // 运营置顶 / 金标公众号 / 官方帖标签（公告 / 消息 / 活动）
        binding.tvPinned.isVisible = item.isPinned
        binding.ivGold.isVisible = item.isGold
        val tag = item.postTag
        binding.tvTag.isVisible = tag.isNotBlank()
        if (tag.isNotBlank()) binding.tvTag.text = squareTagLabel(tag)
        binding.tvTime.text = SquareTime.display(this, item.createdAt)
        bindAvatar(item)
        renderContent(item)
        binding.tvViews.text = item.viewNum.toString()
        binding.tvCommentCount.text = item.commentNum.toString()
        binding.tvLikeCount.text = item.likeNum.toString()

        when {
            item.isAuditing -> {
                binding.tvStatus.isVisible = true
                binding.tvStatus.text = getString(R.string.square_status_auditing)
            }

            item.isRejected -> {
                binding.tvStatus.isVisible = true
                binding.tvStatus.text = getString(R.string.square_status_rejected)
            }

            else -> binding.tvStatus.isVisible = false
        }

        renderLike(item.isLiked)

        SquareImageGrid.bind(binding.llImages, item.images) { index ->
            SquareImagePreviewActivity.start(this, item.images, index)
        }

        val mine = isMine(item.authorAddr)
        // 自己的帖：右上角删除；他人的帖：右上角举报
        binding.ctbTitle.setRightVisible(true)
        binding.ctbTitle.setRightText(
            getString(if (mine) R.string.square_action_delete else R.string.square_report)
        )

        // 编辑入口：自己的帖 + 在可编辑时限内（个人/公众号帖发布后 20 分钟，官方帖不限时）
        // + 广场未限制发帖（canPost=false 时隐藏改帖）
        binding.tvEdit.isVisible = mine && item.editable() && SquareGate.canPost
        binding.tvEdit.setOnClickListener { openEdit(item) }

        // 关注按钮：详情页也可对作者关注（自己的帖不显示）
        setupFollow(item)

        // 只有公众号作者可以开关评论；canPost=false（半关闭）时隐藏
        binding.llCommentSwitch.isVisible = mine && item.isOfficial && SquareGate.canPost
        binding.svComment.isOpened = commentsEnabled
        binding.llCommentBar.isVisible = commentsEnabled
        binding.tvCommentsClosed.isVisible = !commentsEnabled
        binding.tvEmptyComments.isVisible = commentsEnabled && comments.isEmpty()
        // 帖子详情返回后，评论头像按最新优先级重新绑定（评论可能先于帖子渲染）
        if (commentRows.isNotEmpty()) {
            commentRows.forEach { bindCommentAvatar(it.second, it.first) }
            resolveCommentAvatars(comments)
        }
    }

    /**
     * 正文展示
     *
     * 平台官方帖（`contentFormat=markdown`）按 Markdown 渲染；其余帖与旧版服务端按纯文本展示。
     * 链接需要 LinkMovementMethod 才能点击（纯文本帖不设置，保持可长按选择复制的默认行为）。
     */
    private fun renderContent(item: SquarePost) {
        if (item.isMarkdown) {
            binding.tvContent.text = SquareMarkdown.render(item.content)
            binding.tvContent.movementMethod = LinkMovementMethod.getInstance()
        } else {
            binding.tvContent.text = item.content
        }
    }

    /**
     * 加载评论（按楼）
     *
     * `thread=1` 只返回一级评论（楼），每楼带最多 2 条回复，更早的在前；
     * `total` 是楼数（翻页用），`commentCount` 是含回复的可见评论数（显示「N 条评论」）。
     *
     * 分页拉全：服务端单页可能少于请求量，只取第一页会导致评论条数显示不全。
     */
    private fun loadComments() {
        lifecycleScope.launch {
            val all = mutableListOf<SquareComment>()
            var failed = false
            var page = 1
            var total: Int? = null
            while (page <= MAX_COMMENT_PAGE) {
                val resp = SquareApi.comments(postId, page, PAGE_SIZE, thread = true)
                if (!resp.isSuccess) {
                    failed = all.isEmpty()
                    if (failed && !resp.isTransientError()) {
                        // 偶发错误（服务端内部错误/网络异常）不提示，评论列表保持原状
                        toast(squareMessage(resp))
                    }
                    break
                }
                val list = resp.data?.items ?: emptyList()
                all.addAll(list)
                resp.data?.commentCount?.let { total = it }
                val pageTotal = resp.data?.total
                // 本页未取满且已达总数（或服务端未返回总数）时结束翻页
                if (list.isEmpty() ||
                    (list.size < PAGE_SIZE && (pageTotal == null || all.size >= pageTotal))
                ) {
                    break
                }
                page++
            }
            if (failed) return@launch
            comments.clear()
            comments.addAll(all)
            commentTotal = total ?: all.size
            expandedFloors.clear()
            loadedReplies.clear()
            binding.tvCommentCount.text = commentTotal.toString()
            post = post?.copy(commentCount = commentTotal)
            renderComments()
            resolveCommentAvatars(all)
        }
    }

    /**
     * 渲染评论列表（楼 + 楼中楼）
     *
     * 评论区嵌在 ScrollView 里，RecyclerView 的 wrap_content 在嵌套滚动下测高不可靠
     * （评论多时只显示前几条），这里直接往 LinearLayout 逐个填充 item，保证全部展示。
     *
     * 每楼最多展示 2 条回复（服务端返回的更早在前），剩余的点「展开 N 条回复」按楼分页拉取。
     */
    private fun renderComments() {
        binding.llComments.removeAllViews()
        commentRows.clear()
        comments.forEach { floor ->
            val row = layoutInflater.inflate(R.layout.item_square_comment, binding.llComments, false)
            val click = View.OnClickListener { openCommentAuthor(floor) }
            row.findViewById<View>(R.id.iv_avatar).setOnClickListener(click)
            row.findViewById<View>(R.id.tv_name).setOnClickListener(click)
            row.findViewById<View>(R.id.tv_delete).setOnClickListener { confirmDeleteComment(floor) }
            // 点整行 = 回复这条评论
            row.setOnClickListener { startReply(floor) }
            bindCommentRow(row, floor)
            binding.llComments.addView(row)
            commentRows.add(floor to row)

            val replies = loadedReplies[floor.commentId] ?: floor.replies.orEmpty()
            replies.forEach { reply ->
                binding.llComments.addView(buildReplyRow(reply))
            }
            // 还有更早的回复没展示：给一个展开入口（展开后重新渲染整块）
            if (!expandedFloors.contains(floor.commentId) && floor.replyNum > replies.size) {
                val more = layoutInflater.inflate(
                    R.layout.item_square_reply_more, binding.llComments, false
                ) as TextView
                more.text = getString(R.string.square_reply_more, floor.replyNum - replies.size)
                more.setOnClickListener { loadFloorReplies(floor) }
                binding.llComments.addView(more)
            }
        }
        binding.tvCommentsTitle.text = getString(R.string.square_comments_count, commentTotal)
        binding.tvEmptyComments.isVisible = commentsEnabled && comments.isEmpty()
    }

    /**
     * 楼中楼回复行（无头像，缩进展示「A 回复 B：内容」）
     */
    private fun buildReplyRow(reply: SquareComment): View {
        val row = layoutInflater.inflate(R.layout.item_square_reply, binding.llComments, false)
        row.findViewById<TextView>(R.id.tv_reply_name).text = reply.displayName
        val target = reply.replyTargetName
        val hasTarget = target.isNotBlank()
        row.findViewById<View>(R.id.tv_reply_label).isVisible = hasTarget
        row.findViewById<TextView>(R.id.tv_reply_target).apply {
            isVisible = hasTarget
            text = target
        }
        row.findViewById<TextView>(R.id.tv_reply_time).text =
            SquareTime.display(this, reply.createdAt)
        row.findViewById<TextView>(R.id.tv_reply_content).text = reply.content
        val delete = row.findViewById<TextView>(R.id.tv_reply_delete)
        delete.isVisible = isMine(reply.authorAddr)
        delete.setOnClickListener { confirmDeleteComment(reply) }
        // 点回复行 = 回复这条回复（服务端会把楼提升到所属一级评论）
        row.setOnClickListener { startReply(reply) }
        return row
    }

    /**
     * 展开某楼的全部可见回复（GET /posts/:id/comments/:commentId/replies）
     */
    private fun loadFloorReplies(floor: SquareComment) {
        val commentId = floor.commentId
        if (commentId == 0L || expandedFloors.contains(commentId)) return
        lifecycleScope.launch {
            val resp = SquareApi.commentReplies(postId, commentId, 1, MAX_REPLY_SIZE)
            if (resp.isSuccess) {
                loadedReplies[commentId] = resp.data?.items ?: emptyList()
                expandedFloors.add(commentId)
                renderComments()
            } else {
                toast(squareMessage(resp))
            }
        }
    }

    /** 绑定单条评论的内容（头像单独抽离，便于异步解析后重绑） */
    private fun bindCommentRow(row: View, comment: SquareComment) {
        val nameView = row.findViewById<TextView>(R.id.tv_name)
        val timeView = row.findViewById<TextView>(R.id.tv_time)
        val contentView = row.findViewById<TextView>(R.id.tv_content)
        val deleteView = row.findViewById<View>(R.id.tv_delete)
        val avatarView = row.findViewById<View>(R.id.iv_avatar)
        if (comment.isDeleted) {
            // 一级评论已不可见但仍有可见回复：展示占位，不可删除
            nameView.text = getString(R.string.square_comment_deleted)
            nameView.setTextColor(ContextCompat.getColor(this, R.color.color_8E92A3))
            contentView.text = ""
            contentView.isVisible = false
            timeView.text = ""
            deleteView.isVisible = false
            avatarView.alpha = 0.4f
        } else {
            nameView.text = comment.displayName
            nameView.setTextColor(ContextCompat.getColor(this, R.color.biz_text_grey_dark))
            contentView.isVisible = true
            contentView.text = comment.content
            timeView.text = SquareTime.display(this, comment.createdAt)
            deleteView.isVisible = isMine(comment.authorAddr)
            avatarView.alpha = 1f
        }
        bindCommentAvatar(row, comment)
    }

    /** 评论头像：公众号作者评论时优先用公众号头像 */
    private fun bindCommentAvatar(row: View, comment: SquareComment) {
        val official = comment.authorAddr == post?.authorAddr && post?.isOfficial == true
        row.findViewById<ChatAvatarView>(R.id.iv_avatar).load(
            SquareAvatarCache.getForAddress(comment.authorAddr, official),
            defaultAvatar(ChatConst.PRIVATE_CHANNEL)
        )
    }

    /**
     * 评论头像：优先取缓存（服务端/链上资料/本地账户），未缓存时异步查一次链上资料并局部刷新
     *
     * 评论对象不带头像字段，和帖子作者一样按地址从联系人（合约）查询
     */
    private fun resolveCommentAvatars(comments: List<SquareComment>) {
        comments.forEach { comment ->
            val address = comment.authorAddr
            if (address.isNullOrBlank()) return@forEach
            val official = comment.authorAddr == post?.authorAddr && post?.isOfficial == true
            if (SquareAvatarCache.getForAddress(address, official) != null) return@forEach
            resolveSquareAvatar(lifecycleScope, contactManager, address) { addr ->
                commentRows.filter { it.first.authorAddr == addr }
                    .forEach { bindCommentAvatar(it.second, it.first) }
            }
        }
    }

    /**
     * 点击评论头像/昵称 → 进入该用户的动态列表
     */
    private fun openCommentAuthor(comment: SquareComment) {
        val address = comment.authorAddr
        if (address.isNullOrBlank()) return
        val stub = SquarePost(
            authorAddr = address,
            authorName = comment.displayName,
            authorKind = if (post?.isOfficial == true && post?.authorAddr == address) 1 else 0
        )
        startActivity(SquareUserActivity.intent(this, stub, emptyList()))
    }

    /**
     * 作者头像：点击进入该用户的动态列表
     */
    private fun bindAvatar(item: SquarePost) {
        val avatar = binding.ivAvatar
        avatar.load(SquareAvatarCache.getForPost(item), defaultAvatar(ChatConst.PRIVATE_CHANNEL))
        // 头像与昵称点击行为一致：进入该用户的动态列表
        val click = View.OnClickListener {
            if (!item.authorAddr.isNullOrBlank()) {
                startActivity(SquareUserActivity.intent(this, item, listOf(item)))
            }
        }
        avatar.setOnClickListener(click)
        binding.tvAuthor.setOnClickListener(click)
        // 公众号帖已带服务端头像，无需再查链上资料（链上头像仅作为个人帖/无头像时的回退）
        if (item.authorAvatar.isNullOrBlank()) {
            resolveSquareAvatar(lifecycleScope, contactManager, item.authorAddr) {
                // 重新按优先级取（公众号帖用公众号头像，不被链上头像覆盖）
                avatar.load(SquareAvatarCache.getForPost(item), defaultAvatar(ChatConst.PRIVATE_CHANNEL))
            }
        }
    }

    // ==================== 点赞 ====================

    private fun renderLike(liked: Boolean) {
        val color = if (liked) LIKED_COLOR else NORMAL_COLOR
        binding.ivLike.setImageResource(
            if (liked) R.drawable.square_ic_liked else R.drawable.square_ic_like
        )
        binding.ivLike.setColorFilter(color)
        binding.tvLikeCount.setTextColor(color)
    }

    private fun toggleLike() {
        val item = post ?: return
        lifecycleScope.launch {
            val resp = if (item.isLiked) {
                SquareApi.unlikePost(postId)
            } else {
                SquareApi.likePost(postId)
            }
            if (resp.isSuccess && resp.data != null) {
                val liked = resp.data!!.isLiked
                binding.tvLikeCount.text = resp.data!!.likeNum.toString()
                renderLike(liked)
                post = item.copy(likeCount = resp.data!!.likeNum, liked = liked)
            } else {
                toast(squareMessage(resp))
            }
        }
    }

    // ==================== 评论 ====================

    private fun sendComment() {
        if (sending) return
        val content = binding.etComment.text.toString().trim()
        if (content.isEmpty()) {
            toast(R.string.square_content_empty)
            return
        }
        // 回复目标在请求前确定：发送期间用户可能又点了别的评论
        val target = replyTarget
        sending = true
        // 立即给出反馈（清空输入 + 按钮置为提交中），避免等待接口期间以为没点上
        binding.etComment.setText("")
        setSendState(true)
        lifecycleScope.launch {
            val nickname = delegate.current.value?.nickname?.takeIf { it.isNotBlank() }
            val resp = SquareApi.addComment(postId, content, nickname, target?.commentId)
            sending = false
            setSendState(false)
            when {
                resp.isSuccess -> {
                    toast(
                        if (target != null) R.string.square_reply_success
                        else R.string.square_comment_success
                    )
                    cancelReply()
                    loadComments()
                    refreshPostCount()
                }

                resp.isPendingReview -> {
                    toast(R.string.square_tips_comment_pending)
                }

                else -> {
                    // 发送失败时把内容还给用户
                    binding.etComment.setText(content)
                    binding.etComment.setSelection(content.length)
                    toast(squareMessage(resp))
                }
            }
        }
    }

    /**
     * 进入回复状态：点某条评论 / 回复后，输入框上方显示「回复 @昵称」
     */
    private fun startReply(comment: SquareComment) {
        if (!commentsEnabled || comment.isDeleted) return
        replyTarget = comment
        binding.llReplyHint.isVisible = true
        binding.tvReplyHint.text = getString(R.string.square_reply_to, comment.displayName)
        binding.etComment.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(binding.etComment, InputMethodManager.SHOW_IMPLICIT)
    }

    /** 退出回复状态（回到评论帖子） */
    private fun cancelReply() {
        replyTarget = null
        binding.llReplyHint.isVisible = false
    }

    /**
     * 发送按钮的提交中状态
     */
    private fun setSendState(busy: Boolean) {
        binding.btnSend.isEnabled = !busy
        binding.btnSend.alpha = if (busy) 0.6f else 1f
        binding.btnSend.text = getString(
            if (busy) R.string.square_sending else R.string.square_action_send
        )
    }

    /**
     * 评论成功后刷新帖子评论数（服务端计数包含待审内容，这里只做展示刷新）
     *
     * 随后 [loadComments] 会用服务端返回的 commentCount 覆盖成本次拉取的真实值。
     */
    private fun refreshPostCount() {
        commentTotal += 1
        binding.tvCommentCount.text = commentTotal.toString()
        post = post?.copy(commentCount = commentTotal)
    }

    private fun confirmDeleteComment(item: SquareComment) {
        EasyDialog.Builder()
            .setHeaderTitle(getString(R.string.square_delete_comment_confirm))
            .setBottomLeftText(getString(R.string.biz_cancel))
            .setBottomRightText(getString(R.string.biz_confirm))
            .setBottomLeftClickListener(null)
            .setBottomRightClickListener { dialog ->
                dialog.dismiss()
                deleteComment(item)
            }
            .create(this)
            .show()
    }

    private fun deleteComment(item: SquareComment) {
        lifecycleScope.launch {
            val resp = SquareApi.deleteComment(item.commentId)
            if (resp.isSuccess) {
                // 删楼会影响其他楼的回复计数，删回复会影响本楼计数 → 整体重拉
                if (replyTarget?.commentId == item.commentId) cancelReply()
                toast(R.string.square_delete_success)
                loadComments()
            } else {
                toast(squareMessage(resp))
            }
        }
    }

    // ==================== 关闭评论 / 删除动态 ====================

    /**
     * 开关评论
     *
     * 注意：SwitchView 点击后只会进入中间态（OFF2/ON2），必须显式调用
     * [SwitchView.toggleSwitch] 才会完成状态切换（项目内统一约定）。
     */
    private fun changeCommentsEnabled(view: SwitchView?, enabled: Boolean) {
        lifecycleScope.launch {
            val resp = SquareApi.setCommentsEnabled(postId, enabled)
            if (resp.isSuccess) {
                commentsEnabled = enabled
                view?.toggleSwitch(enabled)
                binding.llCommentBar.isVisible = enabled
                binding.tvCommentsClosed.isVisible = !enabled
                binding.tvEmptyComments.isVisible = enabled && comments.isEmpty()
            } else {
                // 失败时把开关拨回原状态
                view?.toggleSwitch(!enabled)
                toast(squareMessage(resp))
            }
        }
    }

    private fun confirmDeletePost(item: SquarePost) {
        EasyDialog.Builder()
            .setHeaderTitle(getString(R.string.square_delete_confirm))
            .setBottomLeftText(getString(R.string.biz_cancel))
            .setBottomRightText(getString(R.string.biz_confirm))
            .setBottomLeftClickListener(null)
            .setBottomRightClickListener { dialog ->
                dialog.dismiss()
                deletePost()
            }
            .create(this)
            .show()
    }

    private fun deletePost() {
        lifecycleScope.launch {
            val resp = SquareApi.deletePost(postId)
            if (resp.isSuccess) {
                toast(R.string.square_delete_success)
                setResult(RESULT_OK)
                finish()
            } else {
                toast(squareMessage(resp))
            }
        }
    }

    /**
     * 进入编辑页（复用发布页的编辑模式，PUT /posts/:id）
     *
     * 返回后重新拉详情：编辑会重新走审核，且若原帖置顶可能被取消置顶
     */
    private fun openEdit(item: SquarePost) {
        needReload = true
        startActivity(PublishSquareActivity.editIntent(this, item))
    }

    // ==================== 关注作者 ====================

    /**
     * 关注按钮：详情页里也能对作者关注（与用户主页同一套实现）
     *
     * 帖子对象不返回 followed，先看内存缓存；未加载过关注列表时拉一次
     * （失败保持未关注，不影响关注操作）。自己的帖 / 无地址时隐藏按钮。
     */
    private fun setupFollow(item: SquarePost) {
        val addr = item.authorAddr.orEmpty()
        if (addr.isBlank() || isMine(addr)) {
            binding.tvFollow.isVisible = false
            return
        }
        followed = SquareFollowCache.isFollowed(addr)
        renderFollowButton()
        if (SquareFollowCache.loaded) return
        lifecycleScope.launch {
            if (SquareFollowCache.ensureLoaded()) {
                // 异步拉取期间用户可能已返回/切换，地址一致才刷新
                if (post?.authorAddr.equals(addr, ignoreCase = true)) {
                    followed = SquareFollowCache.isFollowed(addr)
                    renderFollowButton()
                }
            }
        }
    }

    private fun renderFollowButton() {
        binding.tvFollow.isVisible = true
        binding.tvFollow.setText(if (followed) R.string.square_followed else R.string.square_follow)
        binding.tvFollow.setBackgroundResource(
            if (followed) R.drawable.shape_square_followed_btn else R.drawable.shape_square_follow_btn
        )
        binding.tvFollow.setTextColor(
            ContextCompat.getColor(this, if (followed) R.color.color_8E92A3 else R.color.white)
        )
    }

    private fun toggleFollow() {
        val addr = post?.authorAddr.orEmpty()
        if (addr.isEmpty() || following || isMine(addr)) return
        following = true
        lifecycleScope.launch {
            val resp = if (followed) {
                SquareApi.unfollow(addr)
            } else {
                SquareApi.follow(addr)
            }
            following = false
            if (resp.isSuccess) {
                followed = resp.data?.isFollowed ?: !followed
                SquareFollowCache.markFollowed(addr, followed)
                renderFollowButton()
                toast(
                    if (followed) R.string.square_follow_success
                    else R.string.square_unfollow_success
                )
            } else if (resp.result == SquareCode.FOLLOW_SELF) {
                // 不能关注自己（不排除地址写错/缓存脱敏等意外）
                toast(R.string.square_follow_tips_self)
                binding.tvFollow.isVisible = false
            } else {
                toast(squareMessage(resp))
            }
        }
    }

    private fun isMine(address: String?): Boolean {
        val mine = delegate.getAddress() ?: return false
        return address?.equals(mine, ignoreCase = true) == true
    }
}
