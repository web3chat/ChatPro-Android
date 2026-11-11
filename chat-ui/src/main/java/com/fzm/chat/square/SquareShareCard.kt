package com.fzm.chat.square

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizFileProvider
import com.king.zxing.util.CodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 广场帖子分享图片（海报）
 *
 * 分享到微信等外部 App 前，用 Canvas 画一张卡片：App 图标 + 名称 + 简介 + 作者信息 + 帖子正文（含首图）。
 * 图片保存到 `files/Picture/`，通过 [BizFileProvider]（business 模块已声明该目录）以 content uri 分享。
 *
 * 设计稿宽度 [DESIGN]dp，实际输出 [WIDTH]px（所有尺寸按比例放大，保证清晰度）。
 */
internal object SquareShareCard {

    /** 输出图片宽度（px），微信等场景下清晰度足够 */
    private const val WIDTH = 1080

    /** 设计稿宽度（dp），代码里的尺寸都按这个宽度写 */
    private const val DESIGN = 360f

    /** 正文最多展示行数，超出省略（点击原帖看全文） */
    private const val MAX_CONTENT_LINES = 8

    /** 正文参与排版的最大字数，避免超长文本构建 StaticLayout 过慢 */
    private const val MAX_CONTENT_CHARS = 1200

    /** 图片加载超时（头像/首图加载失败就不画，不阻塞分享） */
    private const val IMAGE_TIMEOUT_MS = 1500L

    private const val FILE_PREFIX = "square_share_"

    /** App 下载页地址（分享图右下角二维码的内容） */
    private const val DOWNLOAD_URL = "https://web3chat.cc"

    private val bgColor = Color.parseColor("#F4F6F9")
    private val cardColor = Color.WHITE
    private val titleColor = Color.parseColor("#1B2A3A")
    private val contentColor = Color.parseColor("#333333")
    private val greyColor = Color.parseColor("#8A97A5")
    private val nameColor = Color.parseColor("#24374E")
    private val officialColor = Color.parseColor("#1B8FE3")
    private val goldColor = Color.parseColor("#F09A16")
    private val dividerColor = Color.parseColor("#EEF0F3")

    /**
     * 生成分享图片，失败（内存不足/绘制异常等）返回 null，由调用方退回文本分享
     */
    suspend fun create(context: Context, post: SquarePost): Bitmap? = withContext(Dispatchers.IO) {
        try {
            build(context, post)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 保存分享图片（同时清掉上一次生成的图，避免缓存目录堆积），返回可分享的 content uri
     */
    fun save(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val dir = File(context.filesDir, "Picture")
            if (!dir.exists()) dir.mkdirs()
            val name = "$FILE_PREFIX${System.currentTimeMillis()}.png"
            val file = File(dir, name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // 只保留最新一张
            dir.listFiles()?.forEach { old ->
                if (old.name.startsWith(FILE_PREFIX) && old.name != name) old.delete()
            }
            FileProvider.getUriForFile(context, BizFileProvider.authority(context), file)
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 绘制 ====================

    private fun build(context: Context, post: SquarePost): Bitmap {
        val scale = WIDTH / DESIGN
        fun dp(value: Float) = value * scale

        val cardInset = dp(10f)
        val cardPadding = dp(18f)
        val contentLeft = cardInset + cardPadding
        val contentRight = WIDTH - cardInset - cardPadding
        val contentWidth = contentRight - contentLeft

        val appName = appName(context)
        val slogan = context.getString(R.string.square_share_card_slogan)
        val footer = context.getString(R.string.square_share_card_footer, appName)
        val time = SquareTime.display(context, post.createdAt)
        // 身份角标：运营台官方号（金色「官方号」）/ 普通公众号（蓝色「公众号」）
        val platformOfficial = post.isPlatformOfficial
        val isOfficial = post.isOfficial || platformOfficial
        val tagText = context.getString(
            if (platformOfficial) R.string.square_platform_official_tag
            else R.string.square_official_tag
        )
        val tagColor = if (platformOfficial) goldColor else officialColor

        // 资源：App 图标、作者头像、帖子首图、下载二维码（都可为空）
        val icon = appIcon(context, dp(40f).toInt())
        val avatar = loadBitmap(context, SquareAvatarCache.getForPost(post), dp(34f).toInt())
        val cover = if (post.images.isNotEmpty()) {
            loadBitmap(context, post.images.first(), WIDTH)
        } else null
        val qr = qrCode(dp(76f).toInt())
        val qrTip = context.getString(R.string.square_share_card_qr_tip, appName)

        val titlePaint = textPaint(dp(16f), titleColor, bold = true)
        val sloganPaint = textPaint(dp(11f), greyColor)
        val namePaint = textPaint(dp(15f), if (isOfficial) officialColor else nameColor, bold = true)
        val tagPaint = textPaint(dp(9f), Color.WHITE, bold = true)
        val timePaint = textPaint(dp(11f), greyColor)
        val footerPaint = textPaint(dp(11f), greyColor)
        val qrTipPaint = textPaint(dp(11f), officialColor, bold = true)
        val contentPaint = TextPaint().apply {
            isAntiAlias = true
            color = contentColor
            textSize = dp(15f)
        }

        // 官方帖正文是 Markdown：海报与详情页保持一致，按 Markdown 富文本排版
        // （标题放大加粗、粗体/斜体/删除线、列表符号、引用、行内代码），
        // 而不是把 `##` `**` 之类的标记原样画在图上。
        val contentText: CharSequence = if (post.isMarkdown) {
            SquareMarkdown.render(post.content.orEmpty().trim().take(MAX_CONTENT_CHARS))
        } else {
            post.content.orEmpty().trim().take(MAX_CONTENT_CHARS)
        }
        val contentLayout = if (contentText.isBlank()) {
            null
        } else {
            StaticLayout.Builder
                .obtain(contentText, 0, contentText.length, contentPaint, contentWidth.toInt())
                .setMaxLines(MAX_CONTENT_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .setLineSpacing(dp(5f), 1f)
                .build()
        }

        // 尺寸
        val headerHeight = dp(40f)
        val avatarSize = dp(34f)
        val coverHeight = dp(126f)
        val qrSize = dp(76f)
        val tagPadding = dp(5f)
        val tagWidth = if (isOfficial) tagPaint.measureText(tagText) + tagPadding * 2 else 0f
        val timeWidth = timePaint.measureText(time)
        val nameMaxWidth = max(
            dp(60f),
            contentWidth - avatarSize - dp(10f) - tagWidth - timeWidth - dp(16f)
        )

        var height = cardInset + cardPadding
        height += headerHeight + dp(14f) + 1f + dp(14f)
        height += avatarSize + dp(12f)
        height += contentLayout?.height ?: 0
        if (cover != null) height += dp(12f) + coverHeight
        height += dp(14f) + 1f + dp(12f) + qrSize
        height += cardPadding + cardInset

        val bitmap = Bitmap.createBitmap(WIDTH, height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bgColor)
        canvas.drawRoundRect(
            RectF(cardInset, cardInset, WIDTH - cardInset, height - cardInset),
            dp(14f), dp(14f), fillPaint(cardColor)
        )

        var y = cardInset + cardPadding

        // ---- 头部：图标 + 名称 + 简介 ----
        val iconRect = RectF(contentLeft, y, contentLeft + dp(40f), y + dp(40f))
        if (icon != null) {
            drawCenterCrop(canvas, icon, iconRect, dp(10f))
        } else {
            canvas.drawRoundRect(iconRect, dp(10f), dp(10f), fillPaint(officialColor))
        }
        val textLeft = iconRect.right + dp(10f)
        val textMaxWidth = contentRight - textLeft
        canvas.drawText(clipText(appName, titlePaint, textMaxWidth), textLeft, y + dp(17f), titlePaint)
        canvas.drawText(clipText(slogan, sloganPaint, textMaxWidth), textLeft, y + dp(33f), sloganPaint)
        y += headerHeight

        // ---- 分割线 ----
        y = drawDivider(canvas, contentLeft, contentRight, y + dp(14f))

        // ---- 作者行：头像 + 昵称（+公众号标识）+ 时间 ----
        y += dp(14f)
        val avatarRect = RectF(contentLeft, y, contentLeft + avatarSize, y + avatarSize)
        if (avatar != null) {
            drawCenterCrop(canvas, avatar, avatarRect, avatarSize / 2f)
        } else {
            canvas.drawCircle(
                avatarRect.centerX(), avatarRect.centerY(), avatarSize / 2f,
                fillPaint(Color.parseColor("#DCE1E8"))
            )
        }
        val name = post.displayName
        val nameText = clipText(name, namePaint, nameMaxWidth)
        val nameBaseline = y + avatarSize / 2f + dp(5.5f)
        var nameX = avatarRect.right + dp(10f)
        canvas.drawText(nameText, nameX, nameBaseline, namePaint)
        if (isOfficial) {
            nameX += namePaint.measureText(nameText) + dp(5f)
            val tagRect = RectF(nameX, nameBaseline - dp(12f), nameX + tagWidth, nameBaseline + dp(3f))
            canvas.drawRoundRect(tagRect, dp(3f), dp(3f), fillPaint(tagColor))
            canvas.drawText(tagText, tagRect.left + tagPadding, nameBaseline - dp(1f), tagPaint)
        }
        canvas.drawText(time, contentRight - timeWidth, nameBaseline, timePaint)
        y += avatarSize

        // ---- 正文 ----
        if (contentLayout != null) {
            y += dp(12f)
            canvas.save()
            canvas.translate(contentLeft, y)
            contentLayout.draw(canvas)
            canvas.restore()
            y += contentLayout.height
        }

        // ---- 帖子首图 ----
        if (cover != null) {
            y += dp(12f)
            drawCenterCrop(canvas, cover, RectF(contentLeft, y, contentRight, y + coverHeight), dp(10f))
            y += coverHeight
        }

        // ---- 底部：来源 + 下载二维码（右下角）----
        y = drawDivider(canvas, contentLeft, contentRight, y + dp(14f))
        y += dp(12f)
        val qrRect = RectF(contentRight - qrSize, y, contentRight, y + qrSize)
        // 二维码四周留白（安静区），保证可扫
        canvas.drawRoundRect(
            RectF(
                qrRect.left - dp(6f), qrRect.top - dp(6f),
                qrRect.right + dp(6f), qrRect.bottom + dp(6f)
            ),
            dp(4f), dp(4f), fillPaint(Color.WHITE)
        )
        if (qr != null) {
            canvas.drawBitmap(qr, null, qrRect, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        // 「扫码下载」与来源文本都和二维码垂直居中（空间不足时截断，保证互不重叠）
        val centerY = qrRect.centerY()
        val tipSpace = qrRect.left - dp(10f) - contentLeft
        val footerText = clipText(footer, footerPaint, tipSpace * 0.5f)
        val tipText = clipText(qrTip, qrTipPaint, tipSpace - footerPaint.measureText(footerText) - dp(12f))
        canvas.drawText(
            tipText,
            qrRect.left - dp(10f) - qrTipPaint.measureText(tipText),
            centerY + dp(4f),
            qrTipPaint
        )
        canvas.drawText(footerText, contentLeft, centerY + dp(4f), footerPaint)

        return bitmap
    }

    // ==================== 小工具 ====================

    private fun appName(context: Context): String {
        return try {
            context.applicationInfo.loadLabel(context.packageManager).toString().ifBlank { "TanXin" }
        } catch (e: Exception) {
            "TanXin"
        }
    }

    private fun appIcon(context: Context, size: Int): Bitmap? {
        return try {
            context.applicationInfo.loadIcon(context.packageManager).toBitmap(size, size)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadBitmap(context: Context, url: String?, size: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            Glide.with(context.applicationContext)
                .asBitmap()
                .load(url)
                .override(size.coerceAtLeast(1))
                .submit()
                .get(IMAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 生成下载二维码（与账号页同款：zxing-lite 的 CodeUtils）
     */
    private fun qrCode(size: Int): Bitmap? {
        return try {
            CodeUtils.createQRCode(DOWNLOAD_URL, size.coerceAtLeast(1))
        } catch (e: Exception) {
            null
        }
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean = false): TextPaint {
        return TextPaint().apply {
            isAntiAlias = true
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun fillPaint(color: Int): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    }

    private fun drawDivider(canvas: Canvas, left: Float, right: Float, y: Float): Float {
        canvas.drawRect(left, y, right, y + 1f, fillPaint(dividerColor))
        return y + 1f
    }

    /**
     * 居中裁剪绘制（可选圆角，radius 为宽高一半时即圆形）
     */
    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, dst: RectF, radius: Float) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val ratio = max(dst.width() / bitmap.width, dst.height() / bitmap.height)
        val srcWidth = (dst.width() / ratio).toInt().coerceIn(1, bitmap.width)
        val srcHeight = (dst.height() / ratio).toInt().coerceIn(1, bitmap.height)
        val src = Rect(
            (bitmap.width - srcWidth) / 2,
            (bitmap.height - srcHeight) / 2,
            (bitmap.width + srcWidth) / 2,
            (bitmap.height + srcHeight) / 2
        )
        canvas.save()
        if (radius > 0f) {
            val path = Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) }
            canvas.clipPath(path)
        }
        canvas.drawBitmap(bitmap, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }

    /**
     * 单行文本超宽时截断加省略号
     */
    private fun clipText(text: String, paint: TextPaint, maxWidth: Float): String {
        if (text.isEmpty() || maxWidth <= 0f) return text
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val available = maxWidth - paint.measureText(ellipsis)
        val count = paint.breakText(text, true, available.coerceAtLeast(1f), null)
        return text.take(count.coerceAtLeast(1)) + ellipsis
    }
}
