package com.fzm.chat.conversation.adapter.msg

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Patterns
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.alibaba.android.arouter.launcher.ARouter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.R
import com.fzm.chat.call.CallInviteLink
import com.fzm.chat.call.CallLauncher
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.model.LinkCard
import com.fzm.chat.core.data.model.isSendType
import com.fzm.chat.databinding.LayoutMsgTextBinding
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.square.SquareCardMessage
import com.fzm.chat.square.SquareDetailActivity
import com.fzm.chat.square.SquareTime
import com.fzm.chat.square.SquarePostLink
import com.zjy.architecture.ext.gone
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.visible
import dtalk.biz.Biz

/**
 * @author zhengjy
 * @since 2020/12/25
 * Description:
 */
class ChatText(listener: ChatMessageClickListener) : ChatBaseItem(listener) {

    override val itemViewType: Int = Biz.MsgType.Text_VALUE

    override fun setupView(holder: BaseViewHolder, item: ChatMessage) {
        val binding = getOrCreateBinding<LayoutMsgTextBinding>(holder)
        if (item.isSendType) {
            binding.root.setBackgroundResource(R.drawable.img_chat_send)
            binding.tvTranslation.setBackgroundResource(R.drawable.bg_translate_block_send)
        } else {
            binding.root.setBackgroundResource(R.drawable.img_chat_receive)
            binding.tvTranslation.setBackgroundResource(R.drawable.bg_translate_block_receive)
        }
        // 广场帖子卡片：正文里带 [[SQUARE_CARD]] 标记时，用卡片替换正文
        val squareCard = if (adapter?.selectable == true) null else SquareCardMessage.parse(item.msg.content)
        // 链接卡片：正文里带 [[LINK_CARD]] 标记时，用卡片替换正文
        val linkCard = if (squareCard == null && adapter?.selectable != true) {
            LinkCard.parse(item.msg.content)
        } else {
            null
        }
        if (squareCard != null) {
            binding.tvMessage.gone()
            binding.llLinkCard.gone()
            binding.llSquareCard.visible()
            // 卡片自带白色圆角背景，去掉气泡底图
            binding.root.setBackgroundResource(0)
            binding.tvSquareName.text = squareCard.name
            binding.tvSquareTime.text = SquareTime.display(context, squareCard.time)
            binding.tvSquareContent.text = squareCard.content

            if (squareCard.image.isNotEmpty()) {
                binding.ivSquareImage.visible()
                binding.ivSquareImage.load(squareCard.image)
            } else {
                binding.ivSquareImage.gone()
                binding.ivSquareImage.setImageDrawable(null)
            }
            binding.llSquareCard.setOnClickListener {
                try {
                    val intent = SquareDetailActivity.intent(context, squareCard.postId, false)
                    if (context !is Activity) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("ChatText", "打开帖子详情失败：${e.message}", e)
                }
            }
            binding.llSquareCard.setOnLongClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener.onChatLayoutLongClick(view, item, this@ChatText)
            }
        } else {
            binding.llSquareCard.gone()
            binding.llSquareCard.setOnClickListener(null)
            binding.llSquareCard.setOnLongClickListener(null)
            if (linkCard != null) {
                binding.tvMessage.gone()
                binding.llLinkCard.visible()
                // 卡片自带白色圆角背景，去掉气泡底图
                binding.root.setBackgroundResource(0)
                binding.tvCardTitle.text = linkCard.title
                if (linkCard.description.isNotEmpty()) {
                    binding.tvCardDesc.visible()
                    binding.tvCardDesc.text = linkCard.description
                } else {
                    binding.tvCardDesc.gone()
                }
                if (linkCard.image.isNotEmpty()) {
                    binding.ivCardThumb.load(linkCard.image, R.drawable.square_ic_md_link)
                } else {
                    binding.ivCardThumb.setImageResource(R.drawable.square_ic_md_link)
                }
                if (linkCard.source.isNotEmpty()) {
                    binding.viewCardDivider.visible()
                    binding.llCardSource.visible()
                    binding.tvCardSource.text = linkCard.source
                } else {
                    binding.viewCardDivider.gone()
                    binding.llCardSource.gone()
                }
                binding.llLinkCard.setOnClickListener { openWebUrl(context, linkCard.url) }
                binding.llLinkCard.setOnLongClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    listener.onChatLayoutLongClick(view, item, this@ChatText)
                }
            } else {
                binding.llLinkCard.gone()
                binding.llLinkCard.setOnClickListener(null)
                binding.llLinkCard.setOnLongClickListener(null)
                binding.tvMessage.visible()
                if (adapter?.selectable == true) {
                    // 多选模式下不打断选择操作
                    binding.tvMessage.text = item.msg.content
                    binding.tvMessage.movementMethod = null
                    binding.tvMessage.isClickable = false
                    binding.tvMessage.isLongClickable = false
                } else {
                    val linkColor = ContextCompat.getColor(context, R.color.biz_color_accent_strong)
                    binding.tvMessage.text = buildLinkText(item.msg.content, linkColor)
                    binding.tvMessage.movementMethod = ChatLinkMovementMethod { event ->
                        // 长按链接：弹出与气泡一致的长按菜单
                        val layout = chatLayout(holder) ?: return@ChatLinkMovementMethod
                        layout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        if (event != null) {
                            listener.onTouchChatMainView(layout, event)
                        }
                        listener.onChatLayoutLongClick(layout, item, this@ChatText)
                    }
                    binding.tvMessage.isClickable = false
                    binding.tvMessage.isLongClickable = false
                }
            }
        }
        // 微信样式：原文下方显示译文块（链接卡片不显示译文）
        val translated = item.translatedText
        if (translated.isNullOrBlank() || linkCard != null || squareCard != null) {
            binding.llTranslate.gone()
        } else {
            binding.llTranslate.visible()
            binding.tvTranslation.text = translated
            binding.llTranslate.setOnClickListener {
                item.translatedText = null
                listener.onTranslateFold(item)
            }
        }
    }
    override fun chatLayout(holder: BaseViewHolder): View? {
        return getOrCreateBinding<LayoutMsgTextBinding>(holder).root
    }

    companion object {

        /** 链接长按时长（毫秒），与系统长按阈值一致 */
        private const val LONG_PRESS_TIMEOUT = 500L

        /** 链接末尾常见标点（避免把句号/右括号算进 URL） */
        private const val TRAILING_CHARS = ".,;:!?)]}，。；：！？）】》\"'"

        /**
         * 把纯文本转成带可点击链接的 Spannable。
         *
         * 识别：`http(s)://…`、`www.…`、`example.com/…`（Patterns.WEB_URL）；
         * 无 scheme 的自动补 `http://`；邮箱里的域名不识别。
         */
        fun buildLinkText(content: String?, linkColor: Int): CharSequence {
            if (content.isNullOrEmpty()) return content.orEmpty()
            val spannable = SpannableStringBuilder(content)
            val matcher = Patterns.WEB_URL.matcher(content)
            while (matcher.find()) {
                val start = matcher.start()
                var end = matcher.end()
                // 跳过邮箱地址里的域名部分
                if (start > 0 && content[start - 1] == '@') continue
                while (end > start && content[end - 1] in TRAILING_CHARS) end--
                if (end <= start) continue
                val raw = content.substring(start, end)
                // 邮箱（user@example.com）不当作网址
                if (raw.contains('@')) continue
                val url = if (raw.startsWith("http://", true) ||
                    raw.startsWith("https://", true)
                ) {
                    raw
                } else {
                    "http://$raw"
                }
                spannable.setSpan(
                    ChatLinkSpan(url, linkColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return spannable
        }

        private fun openWebUrl(context: Context, url: String) {
            // 广场帖子链接：只在聊天里分享，点击直接进帖子详情，不打开浏览器
            val squarePostId = SquarePostLink.parse(url)
            if (squarePostId != null) {
                try {
                    val intent = SquareDetailActivity.intent(context, squarePostId, false)
                    if (context !is Activity) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    android.util.Log.e("ChatText", "打开帖子详情失败：${e.message}", e)
                }
            }
            // 通话房间链接：交给通话页处理（会注入 H5Wallet 并追加当前用户昵称后加载 H5）
            val invite = CallInviteLink.parse(url)
            val activity = context as? FragmentActivity
            if (invite != null && activity != null) {
                try {
                    CallLauncher.join(activity, invite)
                    return
                } catch (e: Exception) {
                    android.util.Log.e("ChatText", "打开通话链接失败：${e.message}", e)
                }
            }
            try {
                // 与发现页「输入网址打开」同一个页面（DappActivity：注入 window.ethereum，可直接连接钱包）
                ARouter.getInstance().build(MainModule.APP_DAPP)
                    .withString(MainModule.PARAM_URL, url)
                    .navigation(context)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    if (context !is Activity) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (ignore: Exception) {
                }
            }
        }

        private class ChatLinkSpan(
            private val url: String,
            private val linkColor: Int
        ) : ClickableSpan() {

            override fun onClick(widget: View) {
                openWebUrl(widget.context, url)
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.color = linkColor
                ds.isUnderlineText = false
            }
        }

        /**
         * 链接触摸处理
         *
         * - 短按链接：打开链接
         * - 长按链接：回调 [onLongClick]（弹出与气泡一致的菜单：复制/转发/翻译等）
         * - 其余情况返回 false，不打断气泡原有的单击/长按
         */
        private class ChatLinkMovementMethod(
            private val onLongClick: (MotionEvent?) -> Unit
        ) : LinkMovementMethod() {

            private val handler = Handler(Looper.getMainLooper())
            private var pressedSpan: ClickableSpan? = null
            private var longPressed = false
            private var downEvent: MotionEvent? = null

            private val longPressRunnable = Runnable {
                if (pressedSpan != null) {
                    longPressed = true
                    onLongClick(downEvent)
                }
            }

            override fun onTouchEvent(
                widget: TextView,
                buffer: Spannable,
                event: MotionEvent
            ): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val span = findSpan(widget, buffer, event)
                        if (span != null) {
                            pressedSpan = span
                            longPressed = false
                            downEvent?.recycle()
                            downEvent = MotionEvent.obtain(event)
                            handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT)
                            Selection.setSelection(
                                buffer,
                                buffer.getSpanStart(span),
                                buffer.getSpanEnd(span)
                            )
                            return true
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (pressedSpan != null && findSpan(widget, buffer, event) !== pressedSpan) {
                            handler.removeCallbacks(longPressRunnable)
                            pressedSpan = null
                            Selection.removeSelection(buffer)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressRunnable)
                        val span = pressedSpan
                        pressedSpan = null
                        Selection.removeSelection(buffer)
                        if (span != null) {
                            if (!longPressed && findSpan(widget, buffer, event) === span) {
                                span.onClick(widget)
                            }
                            return true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        pressedSpan = null
                        Selection.removeSelection(buffer)
                        return true
                    }
                }
                return false
            }

            private fun findSpan(
                widget: TextView,
                buffer: Spannable,
                event: MotionEvent
            ): ClickableSpan? {
                var x = event.x.toInt()
                var y = event.y.toInt()
                x -= widget.totalPaddingLeft
                y -= widget.totalPaddingTop
                x += widget.scrollX
                y += widget.scrollY
                val layout = widget.layout ?: return null
                if (y < 0 || y > layout.height || x < 0) return null
                val line = layout.getLineForVertical(y)
                val offset = layout.getOffsetForHorizontal(line, x.toFloat())
                if (offset < 0 || offset >= buffer.length) return null
                val links = buffer.getSpans(offset, offset, ClickableSpan::class.java)
                return links.firstOrNull()
            }
        }
    }
}
