package com.fzm.chat.square

import android.content.Context
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.utils.defaultAvatar
import com.fzm.chat.biz.widget.ChatAvatarView
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.databinding.ActivitySquarePublishPreviewBinding
import com.zjy.architecture.ext.load
import org.koin.android.ext.android.inject

/**
 * 发布预览：按广场帖子样式预览即将发布的动态（Markdown 渲染 + 图片九宫格）。
 *
 * 只读展示，不修改编辑中的内容；返回后继续编辑或发布。
 */
class SquarePublishPreviewActivity : BizActivity() {

    companion object {
        private const val EXTRA_CONTENT = "previewContent"
        private const val EXTRA_MEDIA = "previewMedia"

        fun intent(context: Context, content: String, media: List<String>): Intent {
            return Intent(context, SquarePublishPreviewActivity::class.java)
                .putExtra(EXTRA_CONTENT, content)
                .putStringArrayListExtra(EXTRA_MEDIA, ArrayList(media))
        }
    }

    private val binding by init { ActivitySquarePublishPreviewBinding.inflate(layoutInflater) }

    private val delegate by inject<LoginDelegate>()
    private val contactManager by inject<ContactManager>()

    override val root: View
        get() = binding.root

    override fun initView() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
        // 正文按正式发布的方式渲染：Markdown → 富文本（标题/加粗/列表等），链接可点
        binding.tvContent.text = SquareMarkdown.render(intent?.getStringExtra(EXTRA_CONTENT))
        binding.tvContent.movementMethod = LinkMovementMethod.getInstance()
        // 图片：编辑模式下已有图 + 新选图，统一按九宫格展示
        SquareImageGrid.bind(
            binding.llImages,
            intent?.getStringArrayListExtra(EXTRA_MEDIA).orEmpty()
        )
        bindAuthor()
    }

    override fun initData() {
    }

    override fun setEvent() {
    }

    /**
     * 预览头部显示当前账号信息（昵称 / 头像），与实际发布后的帖子头部保持一致
     */
    private fun bindAuthor() {
        binding.tvName.text = delegate.current.value?.nickname?.takeIf { it.isNotBlank() }
            ?: delegate.getAddress().orEmpty()
        binding.tvTime.setText(R.string.square_time_just_now)
        val address = delegate.getAddress()
        val avatar = binding.ivAvatar
        avatar.load(SquareAvatarCache.get(address), defaultAvatar(ChatConst.PRIVATE_CHANNEL))
        resolveSquareAvatar(lifecycleScope, contactManager, address) {
            avatar.load(SquareAvatarCache.get(address), defaultAvatar(ChatConst.PRIVATE_CHANNEL))
        }
    }
}
