package com.fzm.chat.square

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.databinding.ActivitySquareMyPostsBinding
import com.fzm.widget.dialog.EasyDialog
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.launch

/**
 * 我的帖子 + 流量汇总（GET /square/app/me/posts）
 *
 * 只返回当前地址的帖：不含已删、不含草稿；含可见 / 隐藏 / 待审 / AI 拒绝。
 * 顶部为累计数字（帖子数 / 阅读 / 点赞 / 评论），下方按时间倒序列出我的帖子。
 */
class SquareMyPostsActivity : BizActivity() {

    companion object {

        private const val PAGE_SIZE = 20

        fun intent(context: Context): Intent {
            return Intent(context, SquareMyPostsActivity::class.java)
        }
    }

    private val binding by init { ActivitySquareMyPostsBinding.inflate(layoutInflater) }

    private val adapter by lazy {
        // 这里全是自己的帖
        SquarePostAdapter { true }.apply {
            onLikeClick = { toggleLike(it) }
            onCommentClick = { openDetail(it) }
            onDeleteClick = { confirmDeletePost(it) }
            onEditClick = { openEdit(it) }
            onContentClick = { openDetail(it) }
            onShareClick = { shareSquarePost(it, lifecycleScope) }
        }
    }

    private var page = 1

    private var loading = false

    /** 从编辑/详情页返回后需要刷新列表 */
    private var needRefresh = false

    override val root: View
        get() = binding.root

    override fun initView() {
        binding.rvPost.layoutManager = LinearLayoutManager(this)
        binding.rvPost.adapter = adapter
        adapter.setEmptyView(R.layout.layout_empty_square)
        adapter.setOnItemClickListener { _, _, position ->
            adapter.data.getOrNull(position)?.let { openDetail(it) }
        }
        binding.refresh.setEnableLoadMore(false)
        binding.refresh.setOnRefreshListener { load(1) }
        binding.refresh.setOnLoadMoreListener { load(page + 1, more = true) }
    }

    override fun initData() {
        load(1)
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
    }

    override fun onResume() {
        super.onResume()
        // 编辑/删除后回到本页刷新列表（昵称/时限/待审状态可能都变了）
        if (needRefresh) {
            needRefresh = false
            load(1)
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
            val resp = SquareApi.mePosts(target, PAGE_SIZE)
            loading = false
            if (resp.isSuccess && resp.data != null) {
                val data = resp.data!!
                val items = data.items
                if (more) {
                    adapter.addData(items.toMutableList())
                } else {
                    adapter.setNewData(items.toMutableList())
                }
                page = target
                val hasMore = items.size >= PAGE_SIZE && data.totalNum > adapter.data.size
                binding.refresh.setEnableLoadMore(hasMore)
                renderSummary(data)
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
     * 删除成功后刷新汇总数字（列表项已本地移除，不整页重载）
     */
    private fun reloadSummary() {
        lifecycleScope.launch {
            val resp = SquareApi.mePosts(1, 1)
            if (resp.isSuccess && resp.data != null) {
                renderSummary(resp.data!!)
            }
        }
    }

    private fun renderSummary(data: SquareMePosts) {
        binding.tvTotalPosts.text = data.postNum.toString()
        binding.tvTotalViews.text = data.viewNum.toString()
        binding.tvTotalLikes.text = data.likeNum.toString()
        binding.tvTotalComments.text = data.commentNum.toString()
    }

    // ==================== 交互 ====================

    private fun openDetail(post: SquarePost) {
        needRefresh = true
        startActivity(SquareDetailActivity.intent(this, post.postId, isOfficial = post.isOfficial))
    }

    /**
     * 编辑自己的帖（PUT /posts/:id；仅可编辑时限内展示入口）
     */
    private fun openEdit(post: SquarePost) {
        needRefresh = true
        startActivity(PublishSquareActivity.editIntent(this, post))
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
                reloadSummary()
            } else {
                toast(squareMessage(resp))
            }
        }
    }
}
