package com.fzm.chat.square

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.text.Layout
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.fzm.chat.R
import com.fzm.chat.core.data.local.ContactManager
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 广场响应码 -> 本地提示文案
 *
 * 服务端 message 有多种语言不可控，已知错误码统一用 App 内文案。
 */
fun Context.squareMessage(resp: SquareResp<*>?): String {
    if (resp == null) {
        return getString(R.string.square_tips_failed)
    }
    return when (resp.result) {
        SquareCode.SUCCESS -> getString(R.string.square_action_success)
        SquareCode.PENDING_REVIEW -> getString(R.string.square_tips_pending)
        SquareCode.CONTENT_REJECTED -> getString(R.string.square_tips_rejected)
        SquareCode.RATE_LIMITED -> getString(R.string.square_tips_rate_limited)
        SquareCode.REPLY_TARGET_INVALID -> getString(R.string.square_tips_reply_invalid)
        SquareCode.INVALID_SIGN, SquareCode.SIGN_EXPIRED ->
            getString(R.string.square_tips_sign_invalid)

        SquareCode.PARAM_ERROR -> resp.message ?: getString(R.string.square_tips_param_error)
        SquareCode.NO_PERMISSION -> getString(R.string.square_tips_no_permission)
        SquareCode.NOT_FOUND -> getString(R.string.square_tips_not_found)
        SquareCode.OFFICIAL_BANNED -> getString(R.string.square_tips_banned)
        SquareCode.COMMENT_CLOSED -> getString(R.string.square_tips_comment_closed)
        SquareCode.APPLY_PENDING -> getString(R.string.square_tips_apply_pending)
        SquareCode.REPORT_DUPLICATE -> getString(R.string.square_report_reported)
        SquareCode.FOLLOW_SELF -> getString(R.string.square_follow_tips_self)
        // 广场关闭 / 不能发帖 / 超过可编辑时间：运营文案可能被改，优先原样展示服务端 message
        SquareCode.SQUARE_CLOSED ->
            resp.message?.takeIf { it.isNotBlank() } ?: getString(R.string.square_closed)

        SquareCode.CANNOT_POST ->
            resp.message?.takeIf { it.isNotBlank() } ?: getString(R.string.square_cannot_post)

        SquareCode.EDIT_EXPIRED ->
            resp.message?.takeIf { it.isNotBlank() } ?: getString(R.string.square_edit_expired)

        SquareCode.USER_BANNED ->
            resp.message?.takeIf { it.isNotBlank() } ?: getString(R.string.square_tips_banned)

        SquareCode.INTERNAL_ERROR -> resp.message ?: getString(R.string.square_tips_failed)
        SquareCode.LOCAL_ERROR -> getString(R.string.square_tips_network)
        else -> resp.message?.takeIf { it.isNotBlank() } ?: getString(R.string.square_tips_failed)
    }
}

/**
 * 官方帖标签（服务端固定中文值）→ 本地文案
 *
 * 未知值原样返回（列表筛选遇到未知值则匹配不到帖，不会出现）。
 */
fun Context.squareTagLabel(tag: String?): String {
    return when (tag) {
        SquareTag.ANNOUNCEMENT -> getString(R.string.square_tag_announcement)
        SquareTag.MESSAGE, SquareTag.LEGACY_NEWS -> getString(R.string.square_tag_message)
        SquareTag.ACTIVITY -> getString(R.string.square_tag_activity)
        else -> tag.orEmpty()
    }
}

/** 官方帖标签允许值（POST /posts 参数错误即非法值） */
object SquareTag {
    const val ANNOUNCEMENT = "公告"

    /** 官方帖标签「消息」（服务端 2026-10-21 由「新闻」改名） */
    const val MESSAGE = "消息"
    const val ACTIVITY = "活动"

    /** 兼容旧数据/旧缓存：服务端响应已不再返回「新闻」 */
    const val LEGACY_NEWS = "新闻"
}

/**
 * 是否为「偶发」错误（服务端内部错误 / 网络异常）
 *
 * 广场服务端在海外，网络抖动或服务端偶发异常都会返回 -1000（内部错误）/ -10001（本地网络），
 * 这类错误在下拉刷新、加载列表/评论时**不弹 toast**（偶发却每次弹提示，体验很差），
 * 保持原有内容、让用户再下拉重试即可。
 *
 * 业务类错误（签名失效/无权限/内容不存在/限流等）以及用户主动操作（发帖/评论/点赞/删除）仍照常提示。
 */
internal fun SquareResp<*>?.isTransientError(): Boolean {
    if (this == null) return true
    return result == SquareCode.INTERNAL_ERROR || result == SquareCode.LOCAL_ERROR
}

/**
 * 分享广场帖子到外部 App（微信、QQ、系统分享面板等）
 *
 * 主流程：生成一张分享图片（App 图标 + 名称 + 简介 + 作者信息 + 帖子内容），以图片形式分享；
 * 图片生成失败时退回纯文本分享，保证始终能分享出去。
 *
 * @param scope 生成图片/发起分享所用的协程作用域（页面级 lifecycleScope）
 */fun Context.shareSquarePost(post: SquarePost, scope: CoroutineScope) {
    scope.launch {
        val uri = SquareShareCard.create(this@shareSquarePost, post)?.let {
            SquareShareCard.save(this@shareSquarePost, it)
        }
        if (uri == null) {
            shareSquarePostText(post)
            return@launch
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            buildSquareShareText(post).takeIf { it.isNotBlank() }
                ?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.square_share_chooser)))
        } catch (e: Exception) {
            toast(R.string.square_tips_failed)
        }
    }
}

/**
 * 纯文本分享（图片生成失败时的兜底）：正文 + 作者署名
 */
private fun Context.shareSquarePostText(post: SquarePost) {
    val text = buildSquareShareText(post)
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        startActivity(Intent.createChooser(intent, getString(R.string.square_share_chooser)))
    } catch (e: Exception) {
        toast(R.string.square_tips_failed)
    }
}

/**
 * 分享文本：正文 + 作者署名（图片分享时作为附带文本，纯文本分享时作为正文）
 */
private fun buildSquareShareText(post: SquarePost): String {
    return buildString {
        // 官方帖正文是 Markdown，分享出去先转纯文本（否则带一堆标记）
        val content = if (post.isMarkdown) {
            SquareMarkdown.toPlainText(post.content)
        } else {
            post.content.orEmpty()
        }
        content.takeIf { it.isNotBlank() }?.let { append(it) }
        val author = post.displayName
        if (author.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append("—— ").append(author)
        }
    }
}

/** 广场公众号昵称品牌色（与公众号标识同色） */
private val SQUARE_OFFICIAL_NAME_COLOR = Color.parseColor("#1B8FE3")

/**
 * 广场作者昵称样式：公众号更显眼（品牌蓝 + 字号比个人大一号）
 *
 * @param baseSizeSp 个人昵称字号，公众号在此基础上 +1sp
 */
internal fun TextView.bindSquareAuthor(name: String?, official: Boolean, baseSizeSp: Float = 15f) {
    text = name.orEmpty()
    setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSizeSp + if (official) 1f else 0f)
    setTextColor(
        if (official) SQUARE_OFFICIAL_NAME_COLOR
        else ContextCompat.getColor(context, R.color.biz_text_grey_dark)
    )
}

/**
 * 账号身份标识（昵称后的胶囊角标）
 *
 * - 运营台官方账号（`authorAddr = square-official`）：金色「官方号」
 * - 普通公众号（`authorKind = 1`）：蓝色「公众号」（原样式）
 *
 * @param official 是否公众号
 * @param platformOfficial 是否运营台官方账号（可带分类标签的官方帖作者）
 */
internal fun TextView.bindSquareOfficialTag(official: Boolean, platformOfficial: Boolean = false) {
    val visible = official || platformOfficial
    isVisible = visible
    if (!visible) return
    setText(
        if (platformOfficial) R.string.square_platform_official_tag
        else R.string.square_official_tag
    )
    setBackgroundResource(
        if (platformOfficial) R.drawable.shape_square_official_tag_gold
        else R.drawable.shape_square_official_tag
    )
}

/**
 * 昵称「让位」限宽：昵称过长先省略号，保证同一行里的角标（公众号 / 官方号）优先完整显示
 *
 * 昵称与角标同在水平 LinearLayout 里时，昵称 wrap_content 测量时会先吃掉整行宽度，
 * 排在后面的角标被压成 0 宽（长昵称下角标直接消失）。这里按「行宽 - 角标宽」给昵称设 maxWidth：
 * 昵称短时仍按实际宽度（角标紧跟其后），只有昵称超长时才提前省略。
 *
 * ⚠️ 角标宽度不能只信 measuredWidth —— 被昵称挤压时它是个截断值，要用文本理想宽度兜底。
 *
 * @param row 与角标同行的容器（match_parent，布局完成后读取其宽度）
 */
internal fun TextView.limitSquareNameWidth(row: View, tag: TextView) {
    row.doOnLayout {
        // 行还没测量出宽度时先不设限，等下一次布局再算
        if (row.width <= 0) return@doOnLayout
        val tagWidth = if (tag.isVisible) tag.squareTagWidth() else 0
        val limit = (row.width - tagWidth).coerceAtLeast(0)
        if (this@limitSquareNameWidth.maxWidth != limit) {
            this@limitSquareNameWidth.maxWidth = limit
        }
    }
}

/** 角标完整显示所需宽度：文本理想宽 + 左右内边距 + 左边距（实测与理想值取大，避免挤压后算小） */
private fun TextView.squareTagWidth(): Int {
    // getDesiredWidth 不接受可空类型，这里兜底空串
    val tagText: CharSequence = text ?: ""
    val textWidth = ceil(Layout.getDesiredWidth(tagText, paint).toDouble()).toInt() +
        paddingStart + paddingEnd
    val margin = (layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
    return maxOf(measuredWidth, textWidth) + margin
}

/**
 * 可选中文本（textIsSelectable）的轻点回调
 *
 * ⚠️ textIsSelectable 的 TextView 会自己处理触摸与焦点，直接 setOnClickListener 在列表里经常收不到点击
 * （尤其是选中过文字之后、第一次点按）。这里手动在 ACTION_UP 判定「轻点」再回调：
 * - 滑动超过 touchSlop（列表滚动）：不触发
 * - 长按（选文字）：不触发
 * - 按下时已存在选区（本次点按用于取消选区）：不触发
 */
internal fun TextView.bindSelectableTap(onTap: () -> Unit) {
    isClickable = true
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    var downX = 0f
    var downY = 0f
    var downTime = 0L
    var selectionAtDown = false
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = SystemClock.uptimeMillis()
                selectionAtDown = hasSelection()
            }

            MotionEvent.ACTION_UP -> {
                val moved = abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                val longPress = SystemClock.uptimeMillis() - downTime > longPressTimeout
                if (downTime > 0L && !moved && !longPress && !selectionAtDown) {
                    onTap()
                }
                downTime = 0L
            }

            MotionEvent.ACTION_CANCEL -> downTime = 0L
        }
        false
    }
}

/**
 * 帖子九宫格图片
 *
 * 服务端只返回 OSS 图片 URL，这里按 3 列铺开（最多 9 张）；
 * 只有 1~2 张时按「单图铺满整行 / 双图各占半宽」展示，不留右侧空白。
 */
object SquareImageGrid {

    private const val COLUMN = 3

    /**
     * @param onClick 图片点击回调，下标为图片在 [urls] 中的位置
     */
    fun bind(container: LinearLayout, urls: List<String>, onClick: ((Int) -> Unit)? = null) {
        // 同一组图片重复绑定（如点赞等局部刷新）时不再重建视图，避免图片重新加载产生闪烁
        val key = urls.joinToString("|")
        if (container.tag == key) {
            if (onClick != null) attachClick(container, onClick)
            return
        }
        container.tag = key
        container.removeAllViews()
        if (urls.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val context = container.context
        val density = container.resources.displayMetrics.density
        // 图片间距 3dp（原 6dp，缩小一半）
        val gap = (3 * density).toInt()
        val placeholderColor = 0xFFF3F4F6.toInt()
        urls.chunked(COLUMN).forEachIndexed { rowIndex, row ->
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (rowIndex == 0) 0 else gap
                }
            }
            row.forEachIndexed { columnIndex, url ->
                val position = rowIndex * COLUMN + columnIndex
                // 宽度由权重均分父容器（每行 3 张），高度由 SquareImageView 取同宽，不会超出被裁剪
                val image = SquareImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginStart = if (columnIndex == 0) 0 else gap
                    }
                    setBackgroundColor(placeholderColor)
                    load(url)
                    if (onClick != null) {
                        setOnClickListener { onClick(position) }
                    }
                }
                rowView.addView(image)
            }
            // 最后一行不足 3 张时补占位，保证每张图片宽度一致；
            // 只有 1~2 张图时不补：单图铺满整行、双图各占半宽，不再留右侧空白
            if (urls.size > 2) {
                repeat(COLUMN - row.size) {
                    rowView.addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            1,
                            1f
                        ).apply {
                            marginStart = gap
                        }
                    })
                }
            }
            container.addView(rowView)
        }
    }

    /**
     * 图片未变化时只刷新点击回调
     */
    private fun attachClick(container: LinearLayout, onClick: (Int) -> Unit) {
        var index = 0
        for (rowIndex in 0 until container.childCount) {
            val row = container.getChildAt(rowIndex) as? LinearLayout ?: continue
            for (columnIndex in 0 until row.childCount) {
                val child = row.getChildAt(columnIndex) as? SquareImageView ?: continue
                val position = index++
                child.setOnClickListener { onClick(position) }
            }
        }
    }
}

/**
 * 解析广场作者头像
 *
 * 广场帖子对象不返回头像，按地址从联系人（合约）查询并缓存；已缓存或已请求过的地址不再重复请求。
 *
 * @param onResolved 解析到新头像时回调（在主线程），调用方自行刷新对应控件
 */
internal fun resolveSquareAvatar(
    scope: CoroutineScope,
    contactManager: ContactManager,
    address: String?,
    onResolved: (String) -> Unit
) {
    if (address.isNullOrEmpty() || SquareAvatarCache.contains(address)) return
    SquareAvatarCache.markRequested(address)
    scope.launch {
        val avatar = withContext(Dispatchers.IO) {
            try {
                contactManager.getUserInfo(address).getDisplayImage()
            } catch (e: Exception) {
                ""
            }
        }
        if (avatar.isNotEmpty()) {
            SquareAvatarCache.put(address, avatar)
            onResolved(avatar)
        }
    }
}
