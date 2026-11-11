package com.fzm.chat.square

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.databinding.ActivitySquareFollowBinding
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.launch

/**
 * 我的关注 / 我的粉丝（GET /square/app/follows、/followers）
 *
 * 带 [EXTRA_ADDRESS] 时为「看别人的粉丝」（`?address=` 过滤，只读列表，不再区分关注/粉丝）。
 * 用户与公众号都按钱包地址关注；行内可关注 / 取关（幂等），点击进入对方主页。
 * 「我关注的」列表取关后该行直接移除（服务端也会同步）。
 */
class SquareFollowActivity : BizActivity() {

    companion object {

        /** 我关注的 */
        const val TYPE_FOLLOWING = "following"

        /** 关注我的 */
        const val TYPE_FOLLOWERS = "followers"

        private const val EXTRA_TYPE = "type"

        private const val EXTRA_ADDRESS = "address"

        private const val PAGE_SIZE = 50

        /**
         * @param address 传地址时只看该地址的粉丝（别人主页的「粉丝」入口）；
         *                为空时保持原语义：我的关注 / 我的粉丝
         */
        fun intent(context: Context, type: String, address: String? = null): Intent {
            return Intent(context, SquareFollowActivity::class.java)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_ADDRESS, address)
        }
    }

    private val binding by init { ActivitySquareFollowBinding.inflate(layoutInflater) }

    private val adapter by lazy { SquareFollowAdapter() }

    private var type = TYPE_FOLLOWING

    /** 非空 = 看别人的粉丝 */
    private var address = ""

    private var page = 1

    private var loading = false

    /** 关注 / 取关请求进行中，避免连点 */
    private var following = false

    override val root: View
        get() = binding.root

    override fun initView() {
        type = intent?.getStringExtra(EXTRA_TYPE) ?: TYPE_FOLLOWING
        address = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
        // 带地址时只提供「TA 的粉丝」（主页只放了粉丝入口）
        if (address.isNotBlank()) type = TYPE_FOLLOWERS
        val isFollowing = type == TYPE_FOLLOWING
        binding.ctbTitle.setTitle(
            when {
                address.isNotBlank() -> getString(R.string.square_user_followers_title)
                isFollowing -> getString(R.string.square_my_follows)
                else -> getString(R.string.square_my_followers)
            }
        )
        binding.rvFollow.layoutManager = LinearLayoutManager(this)
        binding.rvFollow.adapter = adapter
        binding.tvEmpty.setText(
            if (isFollowing) R.string.square_follow_empty else R.string.square_followers_empty
        )
        binding.refresh.setEnableLoadMore(false)
        binding.refresh.setOnRefreshListener { load(1) }
        binding.refresh.setOnLoadMoreListener { load(page + 1, more = true) }
        adapter.setOnItemClickListener { _, _, position ->
            adapter.data.getOrNull(position)?.let { openUserPage(it) }
        }
        adapter.setOnItemChildClickListener { _, view, position ->
            if (view.id == R.id.tv_follow) {
                adapter.data.getOrNull(position)?.let { toggleFollow(it) }
            }
        }
    }

    override fun initData() {
        load(1)
        // 粉丝列表的按钮状态来自关注列表缓存，未加载过先拉一次
        if (type == TYPE_FOLLOWERS && !SquareFollowCache.loaded) {
            lifecycleScope.launch {
                if (SquareFollowCache.ensureLoaded()) {
                    for (index in adapter.data.indices) adapter.notifyItemChanged(index)
                }
            }
        }
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
    }

    // ==================== 数据加载 ====================

    private fun load(target: Int, more: Boolean = false) {
        if (loading) {
            binding.refresh.finishRefresh(false)
            binding.refresh.finishLoadMore(false)
            return
        }
        loading = true
        lifecycleScope.launch {
            // 带地址 = 看别人的粉丝（?address= 过滤），不带地址 = 我自己的列表
            val targetAddress = address.ifBlank { null }
            val resp = if (type == TYPE_FOLLOWING) {
                SquareApi.follows(target, PAGE_SIZE, targetAddress)
            } else {
                SquareApi.followers(target, PAGE_SIZE, targetAddress)
            }
            loading = false
            if (resp.isSuccess && resp.data != null) {
                val items = resp.data?.items ?: emptyList()
                // 我关注的列表本身就是关注关系，顺手补进缓存（用户主页按钮状态也用得到）
                if (type == TYPE_FOLLOWING && targetAddress == null) {
                    items.forEach { SquareFollowCache.markFollowed(it.address, true) }
                }
                if (more) {
                    adapter.addData(items.toMutableList())
                } else {
                    adapter.setNewData(items.toMutableList())
                }
                page = target
                val hasMore = items.size >= PAGE_SIZE &&
                        (resp.data?.totalNum ?: 0) > adapter.data.size
                binding.refresh.setEnableLoadMore(hasMore)
                showEmpty(adapter.data.isEmpty())
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

    /** 空列表提示（加载完成且没有数据时展示） */
    private fun showEmpty(show: Boolean) {
        binding.tvEmpty.isVisible = show
    }

    // ==================== 交互 ====================

    private fun openUserPage(item: SquareFollowUser) {
        if (item.addr.isBlank()) return
        val stub = SquarePost(
            authorAddr = item.addr,
            authorName = item.name,
            authorKind = item.targetKind
        )
        startActivity(SquareUserActivity.intent(this, stub, emptyList()))
    }

    private fun toggleFollow(item: SquareFollowUser) {
        if (item.addr.isBlank() || following) return
        following = true
        lifecycleScope.launch {
            val followed = SquareFollowCache.isFollowed(item.addr)
            val resp = if (followed) {
                SquareApi.unfollow(item.addr)
            } else {
                SquareApi.follow(item.addr)
            }
            following = false
            if (resp.isSuccess) {
                val nowFollowed = resp.data?.isFollowed ?: !followed
                SquareFollowCache.markFollowed(item.addr, nowFollowed)
                val index = adapter.data.indexOfFirst { it.addr == item.addr }
                if (type == TYPE_FOLLOWING && !nowFollowed) {
                    // 我关注的列表取关后该行消失
                    if (index >= 0) adapter.removeAt(index)
                } else if (index >= 0) {
                    adapter.notifyItemChanged(index)
                }
                showEmpty(adapter.data.isEmpty())
                toast(
                    if (nowFollowed) R.string.square_follow_success
                    else R.string.square_unfollow_success
                )
            } else if (resp.result == SquareCode.FOLLOW_SELF) {
                toast(R.string.square_follow_tips_self)
            } else {
                toast(squareMessage(resp))
            }
        }
    }
}
