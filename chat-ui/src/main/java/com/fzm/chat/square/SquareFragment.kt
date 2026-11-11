package com.fzm.chat.square

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizFragment
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.databinding.FragmentSquareBinding
import com.fzm.widget.dialog.EasyDialog
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 广场（会话页第一个 Tab）
 *
 * 数据来自谈信广场服务端（doc/谈信广场.md）：
 * - 普通用户、公众号都可以发帖
 * - 列表只展示可见帖（status=1），自己的待审/被拒帖请从详情查看
 */
class SquareFragment : BizFragment() {

    companion object {
        private const val PAGE_SIZE = 20

        private val NORMAL_COLOR = Color.parseColor("#8E92A3")
        private val SELECTED_COLOR = Color.parseColor("#32B2F7")
    }

    private val binding by init<FragmentSquareBinding>()

    private val delegate by inject<LoginDelegate>()

    private val contactManager by inject<ContactManager>()

    private val adapter by lazy {
        SquarePostAdapter { isMine(it) }.apply {
            // 广场列表不展示「举报」入口（详情页右上角、用户主页仍保留）
            showReport = false
            onAuthorClick = { openUserPage(it) }
            onContentClick = { openDetail(it) }
            onShareClick = { requireContext().shareSquarePost(it, this@SquareFragment.lifecycleScope) }
            onLikeClick = { toggleLike(it) }
            onCommentClick = { openDetail(it) }
            onDeleteClick = { confirmDeletePost(it) }
        }
    }

    private var page = 1

    private var loading = false

    /** 当前筛选：null 全部 / 0 个人 / 1 公众号 */
    private var kind: Int? = null

    /** 官方帖标签筛选（公告 / 消息 / 活动），与 [kind] 互斥 */
    private var tag: String? = null

    /** 当前广场流：latest 最新 / hot 近 7 天热门 / following 我关注的 */
    private var feed: String = SquareFeed.LATEST

    /** 我的公众号状态 */
    private var officialState: String = SquareOfficialMe.NONE

    private var officialLoading = false

    /** 首屏数据是否已加载（首次进入走骨架屏流程，之后每次切回都是静默刷新） */
    private var initialLoaded = false

    /** 骨架屏呼吸动画 */
    private var skeletonAnim: ObjectAnimator? = null

    /** 空列表提示文案（关注流与普通流不同） */
    private var emptyView: TextView? = null

    /** 列表刷新完成回调（参数：当前列表里最新的帖子 id），会话页据此计算广场未读角标 */
    var onFeedLoaded: ((Long) -> Unit)? = null

    override val root: View
        get() = binding.root

    /** 当前列表里最新的帖子 id（0 = 列表为空），会话页进入广场时用它当已读基准 */
    fun newestPostId(): Long = adapter.data.maxOfOrNull { it.postId } ?: 0L

    override fun initView(view: View, savedInstanceState: Bundle?) {
        binding.rvSquare.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSquare.adapter = adapter
        // 自己持有空视图引用：关注流没关注任何人时文案不一样
        val empty = layoutInflater.inflate(R.layout.layout_empty_square, binding.rvSquare, false)
        emptyView = empty.findViewById(R.id.tv_empty)
        adapter.setEmptyView(empty)
        adapter.setOnItemClickListener { _, _, position ->
            adapter.data.getOrNull(position)?.let { openDetail(it) }
        }
        binding.refresh.setEnableLoadMore(false)
        binding.refresh.setOnRefreshListener {
            loadOfficialState()
            loadFeed(more = false, target = 1)
        }
        binding.refresh.setOnLoadMoreListener { loadFeed(more = true, target = page + 1) }
        binding.btnPublish.setOnClickListener {
            startActivity(
                PublishSquareActivity.intent(
                    requireContext(),
                    isOfficial = officialState == SquareOfficialMe.APPROVED
                )
            )
        }
        binding.ivMyEntry.setOnClickListener {
            // 我的个人信息 / 公众号资料，回来时（onResume）会自动刷新列表
            startActivity(Intent(requireContext(), SquareOfficialActivity::class.java))
        }
        // 评论与回复收件箱
        binding.flInboxEntry.setOnClickListener {
            startActivity(SquareInboxActivity.intent(requireContext()))
        }
        binding.tvFilterAll.setOnClickListener { filterChanged(null) }
        binding.tvFilterPerson.setOnClickListener { filterChanged(0) }
        binding.tvFilterOfficial.setOnClickListener { filterChanged(1) }
        // 官方帖标签分区（tag=公告 / 消息 / 活动，与 kind 互斥）
        binding.tvTagAnnouncement.setOnClickListener { tagChanged(SquareTag.ANNOUNCEMENT) }
        binding.tvTagNews.setOnClickListener { tagChanged(SquareTag.MESSAGE) }
        binding.tvTagActivity.setOnClickListener { tagChanged(SquareTag.ACTIVITY) }
        // 金标公众号墙
        binding.ivGoldEntry.setOnClickListener {
            startActivity(Intent(requireContext(), SquareGoldActivity::class.java))
        }
        binding.llFeedLatest.setOnClickListener { feedChanged(SquareFeed.LATEST) }
        binding.llFeedHot.setOnClickListener { feedChanged(SquareFeed.HOT) }
        binding.llFeedFollowing.setOnClickListener { feedChanged(SquareFeed.FOLLOWING) }
        updateFilterUi()
        selectFeed(SquareFeed.LATEST)
        // 首屏先用骨架屏占位（数据回来后再隐藏）
        showSkeleton(true)
    }

    override fun initData() {
        // 首屏请求放在 onResume（页面真正可见时才加载），避免 Fragment 预创建时就请求接口
    }

    /**
     * 进入广场先问一次开关状态（GET /status）：完全关闭时不拉帖，直接展示运营文案
     */
    private fun loadInitial() {
        lifecycleScope.launch {
            val resp = SquareApi.status()
            SquareGate.update(resp.data)
            applyPostPermission()
            if (SquareGate.canRead) {
                loadFeed(more = false, target = 1)
                loadInboxUnread()
            } else {
                showSkeleton(false)
                showClosed(SquareGate.message)
            }
        }
    }

    override fun setEvent() {

    }

    override fun onResume() {
        super.onResume()
        // 每次页面切到前台都请求接口（未展示在前台时不会请求）：
        // 首次进入先问开关状态 + 拉首屏，之后每次切回都刷新公众号状态与帖子列表
        if (!initialLoaded) {
            initialLoaded = true
            loadInitial()
        } else if (SquareGate.canRead) {
            loadOfficialState()
            loadFeed(more = false, target = 1)
            // 从收件箱返回时未读数可能已变
            loadInboxUnread()
        } else {
            // 广场被运营关闭时，每次回到页面都重新问一次开关状态（恢复后无需重启）
            loadInitial()
        }
    }

    // ==================== 数据加载 ====================

    /** 筛选行选中态（kind 与 tag 互斥；「全部」= 两者都为空） */
    private fun updateFilterUi() {
        val all = kind == null && tag == null
        bindFilterChip(binding.tvFilterAll, all)
        bindFilterChip(binding.tvFilterPerson, kind == 0)
        bindFilterChip(binding.tvFilterOfficial, kind == 1)
        bindFilterChip(binding.tvTagAnnouncement, tag == SquareTag.ANNOUNCEMENT)
        bindFilterChip(binding.tvTagNews, tag == SquareTag.MESSAGE)
        bindFilterChip(binding.tvTagActivity, tag == SquareTag.ACTIVITY)
    }

    /** 筛选 chip：背景/文字色由 selector 响应 selected（选中=品牌蓝渐变胶囊 + 白字加粗） */
    private fun bindFilterChip(chip: TextView, selected: Boolean) {
        chip.isSelected = selected
        chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun filterChanged(value: Int?) {
        if (kind == value && tag == null) return
        kind = value
        tag = null
        updateFilterUi()
        reloadWithSkeleton()
    }

    /** 官方帖标签筛选（与 kind 互斥；再点「全部」取消） */
    private fun tagChanged(value: String) {
        if (tag == value && kind == null) return
        tag = value
        kind = null
        updateFilterUi()
        reloadWithSkeleton()
    }

    private fun selectFeed(value: String) {
        feed = value
        binding.tvFeedLatest.setTextColor(if (value == SquareFeed.LATEST) SELECTED_COLOR else NORMAL_COLOR)
        binding.tvFeedHot.setTextColor(if (value == SquareFeed.HOT) SELECTED_COLOR else NORMAL_COLOR)
        binding.tvFeedFollowing.setTextColor(
            if (value == SquareFeed.FOLLOWING) SELECTED_COLOR else NORMAL_COLOR
        )
        binding.vFeedLatest.isVisible = value == SquareFeed.LATEST
        binding.vFeedHot.isVisible = value == SquareFeed.HOT
        binding.vFeedFollowing.isVisible = value == SquareFeed.FOLLOWING
        updateEmptyText()
    }

    /**
     * 空列表提示：关注流未关注任何人时服务端返回空列表（不回落最新流），文案换成「还没有关注的人」
     */
    private fun updateEmptyText() {
        emptyView?.setText(
            if (feed == SquareFeed.FOLLOWING) R.string.square_follow_empty
            else R.string.square_empty
        )
    }

    private fun feedChanged(value: String) {
        if (feed == value) return
        selectFeed(value)
        reloadWithSkeleton()
    }

    /**
     * 筛选 / 广场流切换：骨架屏占位 + 重新加载第一页
     *
     * 不清空列表（清空会先闪「暂无内容」），数据回来后直接替换。
     */
    private fun reloadWithSkeleton() {
        page = 1
        binding.refresh.setEnableLoadMore(false)
        showSkeleton(true)
        loadFeed(more = false, target = 1)
    }

    /**
     * 骨架屏显示/隐藏（首屏加载与切换筛选时使用）
     */
    private fun showSkeleton(show: Boolean) {
        binding.llSkeleton.isVisible = show
        if (show) {
            val anim = skeletonAnim ?: ObjectAnimator.ofFloat(binding.llSkeleton, "alpha", 1f, 0.45f)
                .apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                }.also { skeletonAnim = it }
            anim.start()
        } else {
            skeletonAnim?.cancel()
            binding.llSkeleton.alpha = 1f
        }
    }

    private fun loadFeed(more: Boolean, target: Int) {
        if (loading) {
            binding.refresh.finishRefresh(false)
            binding.refresh.finishLoadMore(false)
            return
        }
        loading = true
        lifecycleScope.launch {
            // 默认最新流不传 feed，与旧服务端保持兼容；tag 多值为 OR（这里单选）
            val resp = SquareApi.feed(
                target,
                PAGE_SIZE,
                kind,
                feed = feed.takeIf { it != SquareFeed.LATEST },
                tags = tag?.let { listOf(it) }
            )
            loading = false
            // 数据回来（无论成败）都收起骨架屏
            showSkeleton(false)
            if (resp.isSuccess && resp.data != null) {
                val posts = resp.data?.items ?: emptyList()
                if (more) {
                    adapter.addData(posts.toMutableList())
                } else {
                    adapter.setNewData(posts.toMutableList())
                }
                page = target
                val hasMore = posts.size >= PAGE_SIZE &&
                        (resp.data?.totalNum ?: 0) > adapter.data.size
                binding.refresh.setEnableLoadMore(hasMore)
                // 帖子列表回来 → 广场可读（运营可能刚恢复）；同步隐藏关闭占位
                hideClosed()
                // 服务端帖子已带公众号头像，先同步缓存再解析链上头像
                SquareAvatarCache.absorbPosts(posts)
                resolveAvatars(posts)
                // 首屏 / 刷新完成：通知会话页更新广场未读基准
                if (!more) onFeedLoaded?.invoke(newestPostId())
                if (more) binding.refresh.finishLoadMore(true) else binding.refresh.finishRefresh(true)
            } else {
                if (more) binding.refresh.finishLoadMore(false) else binding.refresh.finishRefresh(false)
                // 广场已完全关闭：隐藏列表区，全屏展示运营文案（不要当普通错误提示）
                if (resp.result == SquareCode.SQUARE_CLOSED) {
                    showSkeleton(false)
                    showClosed(resp.message)
                    return@launch
                }
                // 下拉刷新/筛选切换失败（列表可能还有旧数据）也要提示，避免静默失败；
                // 但服务端偶发错误（内部错误/网络异常）不提示，保留原列表让用户再下拉重试
                if (!resp.isTransientError() && (!more || adapter.data.isEmpty())) {
                    requireContext().toast(requireContext().squareMessage(resp))
                }
            }
        }
    }

    private fun loadOfficialState() {
        if (officialLoading) return
        officialLoading = true
        lifecycleScope.launch {
            val resp = SquareApi.officialMe()
            officialLoading = false
            if (resp.isSuccess) {
                officialState = resp.data?.currentState ?: SquareOfficialMe.NONE
                // 缓存本人公众号头像，官方帖优先展示（个人帖不受影响）
                SquareAvatarCache.cacheOfficial(resp.data, delegate.getAddress())
                // official/me 带广场开关（与 GET /status 同结构），进广场时同步一次
                SquareGate.update(resp.data?.square)
                applyPostPermission()
            }
        }
    }

    /**
     * 收件箱未读数：取 `read=0` 的第一页，未读数由响应里的 `unreadCount` 给出
     *
     * 失败静默（角标保持原样，不误导用户）；广场关闭时不请求。
     */
    private fun loadInboxUnread() {
        if (!SquareGate.canRead) return
        lifecycleScope.launch {
            val resp = SquareApi.replies(1, 1, read = 0)
            if (resp.isSuccess) renderInboxBadge(resp.data?.unread ?: 0)
        }
    }

    private fun renderInboxBadge(count: Int) {
        binding.tvInboxBadge.isVisible = count > 0
        binding.tvInboxBadge.text = if (count > 99) "99+" else count.toString()
    }

    /**
     * 广场开关：canPost=false 时隐藏发帖入口（详情/我的帖的编辑入口共读 [SquareGate]）
     */
    private fun applyPostPermission() {
        binding.btnPublish.isVisible = SquareGate.canPost && SquareGate.canRead
    }

    /**
     * 广场完全关闭：不拉帖，全屏展示运营文案（文案可能被改，原样展示，不写死「失败」）
     */
    private fun showClosed(message: String?) {
        binding.llClosed.isVisible = true
        binding.tvClosed.text = message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.square_closed)
    }

    private fun hideClosed() {
        binding.llClosed.isVisible = false
    }

    // ==================== 交互 ====================

    private fun isMine(post: SquarePost): Boolean {
        val address = delegate.getAddress() ?: return false
        return post.authorAddr?.equals(address, ignoreCase = true) == true
    }

    private fun openDetail(post: SquarePost) {
        // 返回时 onResume 会自动刷新列表
        startActivity(
            SquareDetailActivity.intent(
                requireContext(),
                post.postId,
                isOfficial = officialState == SquareOfficialMe.APPROVED
            )
        )
    }

    /**
     * 点击头像：查看该用户发布过的动态
     */
    private fun openUserPage(post: SquarePost) {
        if (post.authorAddr.isNullOrBlank()) return
        // 返回时 onResume 会自动刷新列表
        startActivity(SquareUserActivity.intent(requireContext(), post, adapter.data))
    }

    private fun toggleLike(post: SquarePost) {
        val mine = isMine(post)
        lifecycleScope.launch {
            val resp = if (post.isLiked) {
                SquareApi.unlikePost(post.postId)
            } else {
                SquareApi.likePost(post.postId)
            }
            if (resp.isSuccess && resp.data != null) {
                updatePost(post, resp.data!!.likeNum, resp.data!!.isLiked)
            } else {
                requireContext().toast(requireContext().squareMessage(resp))
            }
        }
    }

    private fun updatePost(post: SquarePost, likeCount: Int, liked: Boolean) {
        val index = adapter.data.indexOfFirst { it.postId == post.postId }
        if (index < 0) return
        adapter.data[index] = post.copy(likeCount = likeCount, liked = liked)
        // 只刷新点赞区域，避免整项重绑导致图片重新加载发生闪烁
        adapter.notifyItemChanged(index, bundleOf(SquarePostAdapter.PAYLOAD_LIKE to true))
    }

    /**
     * 解析帖子作者头像（查到后用 payload 局部刷新对应项）
     *
     * 公众号帖已由服务端返回 authorAvatar，无需再查链上资料
     */
    private fun resolveAvatars(posts: List<SquarePost>) {
        posts.forEach { post ->
            if (!post.authorAvatar.isNullOrBlank()) return@forEach
            val address = post.authorAddr?.takeIf { it.isNotBlank() } ?: return@forEach
            resolveSquareAvatar(lifecycleScope, contactManager, address) {
                notifyAvatarChanged(address)
            }
        }
    }

    private fun notifyAvatarChanged(address: String) {
        adapter.data.forEachIndexed { index, post ->
            if (post.authorAddr == address) {
                adapter.notifyItemChanged(
                    index,
                    bundleOf(SquarePostAdapter.PAYLOAD_AVATAR to true)
                )
            }
        }
    }

    private fun confirmDeletePost(post: SquarePost) {
        EasyDialog.Builder()
            .setHeaderTitle(getString(R.string.square_delete_confirm))
            .setBottomLeftText(getString(R.string.biz_cancel))
            .setBottomRightText(getString(R.string.biz_confirm))
            .setBottomLeftClickListener(null)
            .setBottomRightClickListener { dialog ->
                dialog.dismiss()
                deletePost(post)
            }
            .create(requireActivity())
            .show()
    }

    private fun deletePost(post: SquarePost) {
        lifecycleScope.launch {
            val resp = SquareApi.deletePost(post.postId)
            if (resp.isSuccess) {
                val index = adapter.data.indexOfFirst { it.postId == post.postId }
                if (index >= 0) {
                    adapter.data.removeAt(index)
                    adapter.notifyItemRemoved(index)
                }
                requireContext().toast(getString(R.string.square_delete_success))
            } else {
                requireContext().toast(requireContext().squareMessage(resp))
            }
        }
    }
}
