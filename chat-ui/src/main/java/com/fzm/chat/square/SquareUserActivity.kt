package com.fzm.chat.square

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.utils.defaultAvatar
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.databinding.ActivitySquareUserBinding
import com.fzm.widget.dialog.EasyDialog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zjy.architecture.ext.clipboardManager
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 用户主页：某个作者发布过的动态（广场列表点击头像进入）
 *
 * 数据来源：`GET /square/app/posts?authorAddr=xxx`（见对接文档 6.1）。
 * 进入页面时先展示列表页已加载的该作者动态，避免白屏。
 */
class SquareUserActivity : BizActivity() {

    companion object {
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_AUTHOR_OFFICIAL = "authorOfficial"
        private const val EXTRA_POSTS = "posts"

        private const val PAGE_SIZE = 20

        /**
         * @param post   点击的帖子（提供作者地址/名称）
         * @param loaded 列表已加载的全部帖子（用于进入页面时先展示该作者已有的动态）
         */
        fun intent(context: Context, post: SquarePost, loaded: List<SquarePost>): Intent {
            val address = post.authorAddr.orEmpty()
            return Intent(context, SquareUserActivity::class.java)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_NAME, post.displayName)
                .putExtra(EXTRA_AUTHOR_OFFICIAL, post.isOfficial)
                .putExtra(EXTRA_POSTS, Gson().toJson(loaded.filter { it.authorAddr == address }))
        }
    }

    private val binding by init { ActivitySquareUserBinding.inflate(layoutInflater) }

    private val delegate by inject<LoginDelegate>()

    private val contactManager by inject<ContactManager>()

    private val adapter by lazy {
        SquarePostAdapter { isMine(it) }.apply {
            onLikeClick = { toggleLike(it) }
            onCommentClick = { openDetail(it) }
            onDeleteClick = { confirmDeletePost(it) }
            onReportClick = { SquareReportActivity.start(this@SquareUserActivity, it) }
            onContentClick = { openDetail(it) }
            onShareClick = { shareSquarePost(it, lifecycleScope) }
        }
    }

    private var address = ""

    /** 该用户是否公众号（用于头像优先取公众号头像） */
    private var authorOfficial = false

    private var page = 1

    private var loading = false

    /** 我是否已关注该地址（帖子对象不带 followed，用关注列表缓存维护） */
    private var followed = false

    /** 关注/取关请求进行中，避免连点 */
    private var following = false

    /** 该用户的关注数 / 粉丝数（GET /follows、/followers 的 total） */
    private var followNum = 0

    private var followerNum = 0

    /** 计数是否已成功拉取（失败时不展示，避免显示 0 误导） */
    private var countsLoaded = false

    override val root: View
        get() = binding.root

    override fun initView() {
        address = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
        if (address.isEmpty()) {
            finish()
            return
        }
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val official = intent?.getBooleanExtra(EXTRA_AUTHOR_OFFICIAL, false) ?: false
        authorOfficial = official
        binding.ctbTitle.setTitle(name.ifEmpty { getString(R.string.square_user_posts) })
        binding.tvName.bindSquareAuthor(name.ifEmpty { address }, official, baseSizeSp = 17f)
        // 身份角标：运营台官方账号（authorAddr = square-official）展示金色「官方号」
        val platformOfficial = isPlatformOfficialAddress(address)
        binding.tvOfficialTag.bindSquareOfficialTag(official, platformOfficial)
        binding.tvAddress.text = address
        // 复制个人地址
        binding.ivCopyAddress.setOnClickListener {
            val data = ClipData.newPlainText("address", address)
            clipboardManager?.setPrimaryClip(data)
            toast(R.string.chat_tips_address_copied)
        }
        binding.rvPost.layoutManager = LinearLayoutManager(this)
        binding.rvPost.adapter = adapter
        // 点列表任意位置都能进帖子详情（正文区域由 onContentClick 处理）
        adapter.setOnItemClickListener { _, _, position ->
            adapter.data.getOrNull(position)?.let { openDetail(it) }
        }
        adapter.setEmptyView(R.layout.layout_empty_square_user)
        binding.refresh.setEnableLoadMore(false)
        binding.refresh.setOnRefreshListener { loadPosts(1) }
        binding.refresh.setOnLoadMoreListener { loadPosts(page + 1, more = true) }
        binding.tvFollow.setOnClickListener { toggleFollow() }
        // 粉丝数可点：进入「TA 的粉丝」列表（自己的主页进「我的粉丝」）
        binding.tvFollowerCount.setOnClickListener {
            startActivity(
                SquareFollowActivity.intent(
                    this,
                    SquareFollowActivity.TYPE_FOLLOWERS,
                    address.takeIf { !isSelf() }
                )
            )
        }
        loadAvatar()
        loadFollowState()
        loadCounts()
    }

    override fun initData() {
        if (address.isEmpty()) return
        val posts = parsePosts()
        adapter.setNewData(posts)
        // 服务端帖子已带公众号头像 → 同步缓存，头部也能展示（含别人的号，无公开资料接口）
        if (SquareAvatarCache.absorbPosts(posts)) loadAvatar()
        loadPosts(1)
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
    }

    // ==================== 数据加载 ====================

    /**
     * 解析列表页带过来的该作者动态（先展示，避免白屏）
     */
    private fun parsePosts(): MutableList<SquarePost> {
        val json = intent?.getStringExtra(EXTRA_POSTS).orEmpty()
        if (json.isEmpty()) return mutableListOf()
        return try {
            val type = object : TypeToken<List<SquarePost>>() {}.type
            val list: List<SquarePost>? = Gson().fromJson(json, type)
            list?.toMutableList() ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun loadPosts(target: Int, more: Boolean = false) {
        if (loading) {
            binding.refresh.finishRefresh(false)
            binding.refresh.finishLoadMore(false)
            return
        }
        loading = true
        lifecycleScope.launch {
            val resp = SquareApi.feed(target, PAGE_SIZE, authorAddr = address)
            loading = false
            if (resp.isSuccess && resp.data != null) {
                val items = resp.data?.items ?: emptyList()
                // 服务端按 authorAddr 过滤，这里再兜底过滤一次
                val matched = items.filter { it.authorAddr?.equals(address, true) == true }
                if (more) {
                    adapter.addData(matched.toMutableList())
                } else if (matched.isNotEmpty()) {
                    adapter.setNewData(matched.toMutableList())
                }
                page = target
                val hasMore = items.size >= PAGE_SIZE
                binding.refresh.setEnableLoadMore(hasMore)
                if (SquareAvatarCache.absorbPosts(matched)) loadAvatar()
                if (more) binding.refresh.finishLoadMore(true) else binding.refresh.finishRefresh(true)
            } else {
                if (more) binding.refresh.finishLoadMore(false) else binding.refresh.finishRefresh(false)
                // 偶发错误（服务端内部错误/网络异常）不提示，保留已展示的内容
                if (!resp.isTransientError() && adapter.data.isEmpty()) {
                    toast(squareMessage(resp))
                }
            }
        }
    }

    /**
     * 关注数 / 粉丝数
     *
     * 列表接口的 `total` 就是总数，这里各取 1 条（pageSize=1）只为拿计数，不拉全量。
     * 两个请求都失败时不显示计数行，避免把「拿不到」显示成 0。
     */
    private fun loadCounts() {
        lifecycleScope.launch {
            val follows = SquareApi.follows(1, 1, address)
            val followers = SquareApi.followers(1, 1, address)
            if (!follows.isSuccess && !followers.isSuccess) return@launch
            if (follows.isSuccess) followNum = follows.data?.totalNum ?: 0
            if (followers.isSuccess) followerNum = followers.data?.totalNum ?: 0
            countsLoaded = true
            renderStats()
        }
    }

    private fun renderStats() {
        if (!countsLoaded) return
        binding.llStats.isVisible = true
        binding.tvFollowCount.text = getString(R.string.square_user_follow_count, followNum)
        binding.tvFollowerCount.text =
            getString(R.string.square_user_follower_count, followerNum)
    }

    private fun loadAvatar() {
        val avatar = binding.ivAvatar
        avatar.load(
            SquareAvatarCache.getForAddress(address, authorOfficial),
            defaultAvatar(ChatConst.PRIVATE_CHANNEL)
        )
        // 服务端已给公众号头像时无需再查链上资料
        val hasServerAvatar = adapter.data.any { it.authorAvatar?.isNotBlank() == true }
        if (hasServerAvatar) return
        resolveSquareAvatar(lifecycleScope, contactManager, address) {
            // 解析到链上头像后重新按优先级取（公众号头像优先，不会被覆盖）
            avatar.load(
                SquareAvatarCache.getForAddress(address, authorOfficial),
                defaultAvatar(ChatConst.PRIVATE_CHANNEL)
            )
        }
    }

    // ==================== 关注 ====================

    /** 是否是我自己的主页（不能关注自己 → 不显示按钮） */
    private fun isSelf(): Boolean {
        val mine = delegate.getAddress() ?: return false
        return address.equals(mine, ignoreCase = true)
    }

    /**
     * 关注状态
     *
     * 帖子对象不返回 followed，先看内存缓存；未加载过关注列表时拉一次（失败保持未关注，不影响关注操作）。
     */
    private fun loadFollowState() {
        if (isSelf()) {
            binding.tvFollow.isVisible = false
            return
        }
        followed = SquareFollowCache.isFollowed(address)
        renderFollowButton()
        if (SquareFollowCache.loaded) return
        lifecycleScope.launch {
            if (SquareFollowCache.ensureLoaded()) {
                followed = SquareFollowCache.isFollowed(address)
                renderFollowButton()
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
        if (address.isEmpty() || following || isSelf()) return
        following = true
        lifecycleScope.launch {
            val resp = if (followed) {
                SquareApi.unfollow(address)
            } else {
                SquareApi.follow(address)
            }
            following = false
            if (resp.isSuccess) {
                followed = resp.data?.isFollowed ?: !followed
                SquareFollowCache.markFollowed(address, followed)
                renderFollowButton()
                // 我关注 / 取关 TA 会改变 TA 的粉丝数，页头计数同步 ±1
                if (countsLoaded) {
                    followerNum = (followerNum + if (followed) 1 else -1).coerceAtLeast(0)
                    renderStats()
                }
                toast(
                    if (followed) R.string.square_follow_success
                    else R.string.square_unfollow_success
                )
            } else if (resp.result == SquareCode.FOLLOW_SELF) {
                // 隐藏自己主页的关注按钮（不排除地址写错/缓存脱敏等意外）
                toast(R.string.square_follow_tips_self)
                binding.tvFollow.isVisible = false
            } else {
                toast(squareMessage(resp))
            }
        }
    }

    // ==================== 交互 ====================

    private fun isMine(post: SquarePost): Boolean {
        val mine = delegate.getAddress() ?: return false
        return post.authorAddr?.equals(mine, ignoreCase = true) == true
    }

    private fun openDetail(post: SquarePost) {
        startActivity(SquareDetailActivity.intent(this, post.postId, isOfficial = post.isOfficial))
    }

    private fun toggleLike(post: SquarePost) {
        lifecycleScope.launch {
            val resp = if (post.isLiked) {
                SquareApi.unlikePost(post.postId)
            } else {
                SquareApi.likePost(post.postId)
            }
            if (resp.isSuccess && resp.data != null) {
                val index = adapter.data.indexOfFirst { it.postId == post.postId }
                if (index >= 0) {
                    adapter.data[index] = post.copy(
                        likeCount = resp.data!!.likeNum,
                        liked = resp.data!!.isLiked
                    )
                    adapter.notifyItemChanged(
                        index,
                        bundleOf(SquarePostAdapter.PAYLOAD_LIKE to true)
                    )
                }
            } else {
                toast(squareMessage(resp))
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
            .create(this)
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
                toast(R.string.square_delete_success)
            } else {
                toast(squareMessage(resp))
            }
        }
    }
}
