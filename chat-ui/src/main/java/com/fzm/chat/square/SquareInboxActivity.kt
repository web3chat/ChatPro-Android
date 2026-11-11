package com.fzm.chat.square

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.databinding.ActivitySquareInboxBinding
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.launch

/**
 * 广场收件箱（GET /square/app/replies、POST /square/app/replies/read）
 *
 * 广场内通知帖子作者（一级评论）或被回复的人（回复），不接 IM。
 * 点条目进入动态详情并顺手标已读；`available=false` 的条目不可点，只提示已失效。
 * 「全部已读」把当前地址的全部未读标为已读。
 */
class SquareInboxActivity : BizActivity() {

    companion object {

        private const val PAGE_SIZE = 20

        fun intent(context: Context): Intent {
            return Intent(context, SquareInboxActivity::class.java)
        }
    }

    private val binding by init { ActivitySquareInboxBinding.inflate(layoutInflater) }

    private val adapter by lazy { SquareInboxAdapter() }

    private var page = 1

    private var loading = false

    /** 「全部已读」请求进行中，避免连点 */
    private var markingAll = false

    /** 是否已进入过一次前台（区分首次加载与返回刷新） */
    private var resumed = false

    override val root: View
        get() = binding.root

    override fun initView() {
        binding.ctbTitle.setTitle(getString(R.string.square_inbox_title))
        binding.ctbTitle.setRightVisible(true)
        binding.ctbTitle.setRightText(getString(R.string.square_inbox_all_read))
        binding.rvInbox.layoutManager = LinearLayoutManager(this)
        binding.rvInbox.adapter = adapter
        binding.refresh.setEnableLoadMore(false)
        binding.refresh.setOnRefreshListener { load(1) }
        binding.refresh.setOnLoadMoreListener { load(page + 1, more = true) }
        adapter.setOnItemClickListener { _, _, position ->
            adapter.data.getOrNull(position)?.let { openItem(it) }
        }
    }

    override fun initData() {
        load(1)
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
        binding.ctbTitle.setOnRightClickListener { markAllRead() }
    }

    override fun onResume() {
        super.onResume()
        // 从详情页返回：状态可能变化（评论被删/帖子不可见），重新拉一次。
        // 首次进入由 initData 触发，这里跳过，避免重复请求。
        if (resumed) {
            load(1)
        } else {
            resumed = true
        }
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
            val resp = SquareApi.replies(target, PAGE_SIZE)
            loading = false
            if (resp.isSuccess && resp.data != null) {
                val items = resp.data?.items ?: emptyList()
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

    private fun showEmpty(show: Boolean) {
        binding.tvEmpty.isVisible = show
    }

    // ==================== 交互 ====================

    private fun openItem(item: SquareReplyItem) {
        if (!item.isAvailable) {
            toast(R.string.square_inbox_unavailable)
            return
        }
        if (!item.isRead) markRead(item)
        startActivity(SquareDetailActivity.intent(this, item.postIdValue, isOfficial = false))
    }

    /** 单条标已读：先本地置为已读（小红点立刻消失），再异步提交 */
    private fun markRead(item: SquareReplyItem) {
        val index = adapter.data.indexOfFirst { it.itemId == item.itemId }
        if (index >= 0) {
            adapter.data[index] = item.copy(read = true)
            adapter.notifyItemChanged(index)
        }
        lifecycleScope.launch { SquareApi.markRepliesRead(ids = listOf(item.itemId)) }
    }

    /** 全部标已读 */
    private fun markAllRead() {
        if (markingAll) return
        if (adapter.data.none { !it.isRead }) {
            toast(R.string.square_inbox_no_unread)
            return
        }
        markingAll = true
        lifecycleScope.launch {
            val resp = SquareApi.markRepliesRead(all = true)
            markingAll = false
            if (resp.isSuccess) {
                for (index in adapter.data.indices) {
                    adapter.data[index] = adapter.data[index].copy(read = true)
                }
                adapter.notifyItemRangeChanged(0, adapter.data.size)
            } else {
                toast(squareMessage(resp))
            }
        }
    }
}
