package com.fzm.chat.square

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.databinding.ActivitySquareGoldBinding
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 金标公众号墙（GET /square/app/officials）
 *
 * 文档《App增量-公众号金标》：金标由运营台超级管理员审核授予（App 不做申请入口）；
 * 列表默认只返回金标号（gold=1），按粉丝数降序、其次发帖数。
 *
 * - 点进某个号用现有 `GET /posts?authorAddr=`（[SquareUserActivity]），不要另开 `feed=gold`
 * - 关注状态用接口返回的 `followed`，关注/取关后本地改该字段即可
 * - 广场完全关闭时本接口与拉帖一样返回 -20413，展示 message
 */
class SquareGoldActivity : BizActivity() {

    companion object {
        private const val PAGE_SIZE = 20
    }

    private val binding by init { ActivitySquareGoldBinding.inflate(layoutInflater) }

    private val delegate by inject<LoginDelegate>()

    private val adapter by lazy {
        SquareGoldAdapter { address -> address.equals(delegate.getAddress(), ignoreCase = true) }
    }

    private var page = 1

    private var loading = false

    /** 关注 / 取关请求进行中，避免连点 */
    private var following = false

    override val root: View
        get() = binding.root

    override fun initView() {
        binding.rvGold.layoutManager = LinearLayoutManager(this)
        binding.rvGold.adapter = adapter
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
            val resp = SquareApi.officials(target, PAGE_SIZE)
            loading = false
            if (resp.isSuccess && resp.data != null) {
                val items = resp.data?.items ?: emptyList()
                // 同步关注缓存：用户主页的「关注/已关注」按钮也读它
                items.forEach { SquareFollowCache.markFollowed(it.addr, it.isFollowed) }
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
                // 广场关闭等业务错误：不显示空列表占位（列表为空也保持原页面）
                showEmpty(false)
            }
        }
    }

    /** 空列表提示（加载完成且没有数据时展示） */
    private fun showEmpty(show: Boolean) {
        binding.tvEmpty.isVisible = show
    }

    // ==================== 交互 ====================

    private fun openUserPage(item: SquareOfficialCard) {
        if (item.addr.isBlank()) return
        val stub = SquarePost(
            authorAddr = item.addr,
            authorName = item.name,
            authorKind = 1
        )
        startActivity(SquareUserActivity.intent(this, stub, emptyList()))
    }

    private fun toggleFollow(item: SquareOfficialCard) {
        if (item.addr.isBlank() || following) return
        following = true
        lifecycleScope.launch {
            val resp = if (item.isFollowed) {
                SquareApi.unfollow(item.addr)
            } else {
                SquareApi.follow(item.addr)
            }
            following = false
            if (resp.isSuccess) {
                val nowFollowed = resp.data?.isFollowed ?: !item.isFollowed
                SquareFollowCache.markFollowed(item.addr, nowFollowed)
                val index = adapter.data.indexOfFirst { it.addr == item.addr }
                if (index >= 0) {
                    adapter.data[index] = item.copy(followed = nowFollowed)
                    adapter.notifyItemChanged(index)
                }
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
