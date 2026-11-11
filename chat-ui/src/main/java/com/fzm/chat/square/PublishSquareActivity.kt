package com.fzm.chat.square

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.facade.annotation.Route
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.utils.onExplain
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.databinding.ActivitySquarePublishBinding
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.oss.MediaType
import com.fzm.chat.router.oss.OssModule
import com.fzm.chat.router.oss.OssService
import com.fzm.chat.router.route
import com.fzm.chat.utils.GlideEngine
import com.fzm.widget.SwitchView
import com.luck.picture.lib.PictureSelector
import com.luck.picture.lib.config.PictureMimeType
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.listener.OnResultCallbackListener
import com.permissionx.guolindev.PermissionX
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import com.zjy.architecture.util.isContent
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 发布广场动态
 *
 * 图片先上传到 OSS（广场图片必须是公网可直读的明文地址，不能带 `$ENC$` 标记），
 * 再连同正文一起提交到广场服务端。
 *
 * 另：H5 的 `window.__tanxinHost.shareSquare(payload)` 会带 [EXTRA_SHARE_TITLE] /
 * [EXTRA_SHARE_DESCRIPTION] / [EXTRA_SHARE_URL] 打开本页并预填内容（用户确认后再发布）。
 */
@Route(path = MainModule.SQUARE_SHARE)
class PublishSquareActivity : BizActivity() {

    companion object {
        private const val EXTRA_IS_OFFICIAL = "isOfficial"
        private const val EXTRA_POST_ID = "editPostId"
        private const val EXTRA_CONTENT = "editContent"
        private const val EXTRA_MEDIA = "editMedia"
        private const val EXTRA_COMMENTS = "editComments"

        /** H5 分享到广场（window.__tanxinHost.shareSquare） */
        private const val EXTRA_SHARE_TITLE = "shareTitle"
        private const val EXTRA_SHARE_DESCRIPTION = "shareDescription"
        private const val EXTRA_SHARE_URL = "shareUrl"

        /** 最多 9 张图片 */
        private const val MAX_IMAGE = 9

        /**
         * 正文最多 5000 字（2026-09-17 从 2000 调整）。
         * 输入时的硬限制由布局 `android:maxLength="5000"` 保证，这里仅在提交时再兑底截断一次。
         * 注意：服务端也必须同步放宽（doc/广场2.md 发帖 content 上限）。
         */
        private const val MAX_CONTENT = 5000

        fun intent(context: Context, isOfficial: Boolean): Intent {
            return Intent(context, PublishSquareActivity::class.java)
                .putExtra(EXTRA_IS_OFFICIAL, isOfficial)
        }

        /**
         * 编辑自己的已发布帖（PUT /posts/:id）
         *
         * 个人帖 / 普通公众号帖仅发布后 20 分钟内可改，运营台官方帖不限时；
         * 入口处已用 [SquarePost.editable] 判断过，超时由服务端返回 -20415。
         */
        fun editIntent(context: Context, post: SquarePost): Intent {
            return Intent(context, PublishSquareActivity::class.java)
                .putExtra(EXTRA_IS_OFFICIAL, post.isOfficial)
                .putExtra(EXTRA_POST_ID, post.postId)
                .putExtra(EXTRA_CONTENT, post.content.orEmpty())
                .putExtra(EXTRA_COMMENTS, post.canComment)
                .putStringArrayListExtra(EXTRA_MEDIA, ArrayList(post.images))
        }
    }

    private val binding by init { ActivitySquarePublishBinding.inflate(layoutInflater) }

    private val delegate by inject<LoginDelegate>()

    private val ossService by route<OssService>(OssModule.APP_OSS)

    /** 已选本地图片路径 */
    private val selected = mutableListOf<String>()

    /** 编辑模式下已上传的图片 URL（可移除；新选图片在 [selected]） */
    private val existingUrls = mutableListOf<String>()

    /** >0 时为编辑模式（PUT /posts/:id） */
    private var editPostId = 0L

    private var commentsEnabled = true

    private var publishing = false

    /** 行首块级标记（标题/引用/无序/有序列表），用于块级样式切换与互斥替换 */
    private val blockPrefixPattern = Regex("^(#{1,6}\\s+|[>]\\s?|[-*+]\\s+|\\d{1,3}[.)]\\s+)")

    private val density by lazy { resources.displayMetrics.density }

    override val root: View
        get() = binding.root

    override fun initView() {
        val isOfficial = intent?.getBooleanExtra(EXTRA_IS_OFFICIAL, false) ?: false
        editPostId = intent?.getLongExtra(EXTRA_POST_ID, 0L) ?: 0L
        if (editPostId > 0) {
            // 编辑模式：回填正文 / 已有图片 / 评论开关，标题与右侧按钮改为「编辑 / 保存」
            binding.ctbTitle.setTitle(getString(R.string.square_edit_title))
            binding.ctbTitle.setRightText(getString(R.string.square_official_save))
            val content = intent?.getStringExtra(EXTRA_CONTENT).orEmpty()
            binding.etContent.setText(content)
            binding.etContent.setSelection(content.length)
            existingUrls.addAll(intent?.getStringArrayListExtra(EXTRA_MEDIA).orEmpty())
            commentsEnabled = intent?.getBooleanExtra(EXTRA_COMMENTS, true) ?: true
        } else {
            prefillSharedContent()
        }
        binding.llCommentSwitch.isVisible = isOfficial
        binding.svComment.isOpened = if (editPostId > 0) commentsEnabled else true
        renderImages()
        buildMarkdownTools()
    }

    /**
     * H5 `window.__tanxinHost.shareSquare(payload)` 预填：标题 + 描述 + 链接。
     *
     * 广场只支持文本/图片，无法生成网页卡片；这里把分享载荷拼成正文，由用户确认后发布。
     */
    private fun prefillSharedContent() {
        val title = intent?.getStringExtra(EXTRA_SHARE_TITLE)?.trim().orEmpty()
        val url = intent?.getStringExtra(EXTRA_SHARE_URL)?.trim().orEmpty()
        if (title.isEmpty() || url.isEmpty()) return
        val description = intent?.getStringExtra(EXTRA_SHARE_DESCRIPTION)?.trim().orEmpty()
        val content = buildString {
            append(title)
            if (description.isNotEmpty()) append("\n\n").append(description)
            append("\n\n").append(url)
        }.take(MAX_CONTENT)
        binding.etContent.setText(content)
        binding.etContent.setSelection(content.length)
    }

    override fun initData() {

    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
        binding.ctbTitle.setOnRightClickListener { publish() }
        binding.svComment.setOnStateChangedListener(object : SwitchView.OnStateChangedListener {
            override fun toggleToOn(view: SwitchView?) {
                commentsEnabled = true
                view?.toggleSwitch(true)
            }

            override fun toggleToOff(view: SwitchView?) {
                commentsEnabled = false
                view?.toggleSwitch(false)
            }
        })
    }

    // ==================== 图片选择与九宫格 ====================

    private fun pickImages() {
        PermissionX.init(this)
            .permissions(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            .onExplain(this)
            .request { allGranted, _, _ ->
                if (!allGranted) {
                    toast(com.fzm.chat.biz.R.string.biz_permission_not_granted)
                    return@request
                }
                PictureSelector.create(this)
                    .openGallery(PictureMimeType.ofImage())
                    .maxSelectNum(MAX_IMAGE - selected.size)
                    .isCompress(true)
                    .isGif(false)
                    .isCamera(false)
                    .theme(R.style.chat_picture_style)
                    .imageEngine(GlideEngine.createGlideEngine())
                    .forResult(object : OnResultCallbackListener<LocalMedia> {
                        override fun onResult(result: MutableList<LocalMedia>?) {
                            if (result.isNullOrEmpty()) return
                            result.forEach { media ->
                                media.path?.takeIf { it.isNotBlank() }?.let { selected.add(it) }
                            }
                            if (selected.size > MAX_IMAGE) {
                                toast(R.string.square_max_images)
                                while (selected.size > MAX_IMAGE) {
                                    selected.removeAt(selected.size - 1)
                                }
                            }
                            renderImages()
                        }

                        override fun onCancel() {
                        }
                    })
            }
    }

    private fun renderImages() {
        binding.llImages.removeAllViews()
        // 缩略图间距 3dp（原 6dp，与广场九宫格保持一致）
        val gap = (3 * density).toInt()
        val padding = (15 * density).toInt() * 2
        val size = ((resources.displayMetrics.widthPixels - padding - gap * 2) / 3)
            .coerceAtLeast((60 * density).toInt())
        val cells = mutableListOf<View>()
        // 编辑模式：已上传图片（服务端 URL）先展示，再展示新选的本地图片
        existingUrls.forEachIndexed { index, url ->
            cells.add(createImageCell(url, index, size, existing = true))
        }
        selected.forEachIndexed { index, path ->
            cells.add(createImageCell(path, existingUrls.size + index, size, existing = false))
        }
        if (existingUrls.size + selected.size < MAX_IMAGE) {
            cells.add(createAddCell(size))
        }
        cells.chunked(3).forEachIndexed { rowIndex, row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (rowIndex == 0) 0 else gap
                }
            }
            row.forEachIndexed { columnIndex, cell ->
                (cell.layoutParams as? LinearLayout.LayoutParams)?.marginStart =
                    if (columnIndex == 0) 0 else gap
                rowView.addView(cell)
            }
            binding.llImages.addView(rowView)
        }
    }

    /**
     * 缩略图单元格（[index] 为「已有图片 + 新选图片」统一列表的下标）
     */
    private fun createImageCell(path: String, index: Int, size: Int, existing: Boolean): View {
        val badgeSize = (18 * density).toInt()
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            addView(ImageView(this@PublishSquareActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                // 圆角缩略图（轮廓来自背景 shape，clipToOutline 裁剪图片内容）
                setBackgroundResource(R.drawable.shape_square_image_cell)
                clipToOutline = true
                if (path.isContent()) {
                    load(Uri.parse(path))
                } else {
                    load(path)
                }
            })
            addView(TextView(this@PublishSquareActivity).apply {
                layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                }
                setBackgroundResource(R.drawable.shape_square_del_badge)
                text = "×"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (existing) {
                        if (index in existingUrls.indices) {
                            existingUrls.removeAt(index)
                            renderImages()
                        }
                    } else {
                        val localIndex = index - existingUrls.size
                        if (localIndex in selected.indices) {
                            selected.removeAt(localIndex)
                            renderImages()
                        }
                    }
                }
            })
        }
    }

    private fun createAddCell(size: Int): View {
        val iconSize = (28 * density).toInt()
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setBackgroundResource(R.drawable.shape_square_add)
            addView(ImageView(this@PublishSquareActivity).apply {
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.CENTER
                }
                setImageResource(R.drawable.icon_home_add)
            })
            setOnClickListener { pickImages() }
        }
    }

    // ==================== 发布 ====================

    private fun publish() {
        if (publishing) return
        // 布局已 maxLength=2000（输入超不过），这里再截断一次兜底
        val content = binding.etContent.text.toString().trim().take(MAX_CONTENT)
        if (content.isEmpty()) {
            toast(R.string.square_content_empty)
            return
        }
        publishing = true
        binding.llLoading.isVisible = true
        lifecycleScope.launch {
            try {
                val urls = mutableListOf<String>()
                // 编辑模式：保留已有图片（服务端 URL）
                urls.addAll(existingUrls)
                for (path in selected) {
                    val url = uploadImage(path)
                    if (url.isEmpty()) {
                        toast(R.string.square_upload_failed)
                        return@launch
                    }
                    urls.add(url)
                }
                val nickname = delegate.current.value?.nickname?.takeIf { it.isNotBlank() }
                val resp = if (editPostId > 0) {
                    SquareApi.updatePost(
                        editPostId, content, nickname, urls, commentsEnabled,
                        SquarePost.CONTENT_MARKDOWN
                    )
                } else {
                    SquareApi.publish(
                        content, nickname, urls, commentsEnabled,
                        SquarePost.CONTENT_MARKDOWN
                    )
                }
                when {
                    resp.isSuccess -> {
                        toast(
                            if (editPostId > 0) R.string.square_edit_success
                            else R.string.square_publish_success
                        )
                        setResult(RESULT_OK)
                        finish()
                    }

                    resp.isPendingReview -> {
                        // 发布/编辑都按「成功待审」处理
                        toast(R.string.square_tips_pending)
                        setResult(RESULT_OK)
                        finish()
                    }

                    else -> toast(squareMessage(resp))
                }
            } finally {
                publishing = false
                binding.llLoading.isVisible = false
            }
        }
    }

    /**
     * 上传图片到 OSS，encrypt=false 保证链接可公网直读
     */
    private suspend fun uploadImage(path: String): String {
        return try {
            if (path.isContent()) {
                ossService?.uploadMedia(null, path.toUri(), MediaType.PICTURE, false) ?: ""
            } else {
                ossService?.uploadMedia(null, path, MediaType.PICTURE, false) ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    // ==================== Markdown 编辑工具 ====================

    private class MdTool(val icon: Int, val desc: Int, val action: () -> Unit)

    /**
     * 构建 Markdown 工具栏：行内样式（粗体/斜体/删除线）+ 块级样式（标题/列表/引用）+ 代码/链接/分割线
     */
    private fun buildMarkdownTools() {
        val tools = listOf(
            MdTool(R.drawable.square_ic_md_bold, R.string.square_md_bold) { wrapInline("**", "**") },
            MdTool(R.drawable.square_ic_md_italic, R.string.square_md_italic) { wrapInline("*", "*") },
            MdTool(R.drawable.square_ic_md_strike, R.string.square_md_strike) { wrapInline("~~", "~~") },
            MdTool(R.drawable.square_ic_md_heading, R.string.square_md_heading) { toggleHeading() },
            MdTool(R.drawable.square_ic_md_bullet, R.string.square_md_bullet_list) { toggleBlock("- ") },
            MdTool(R.drawable.square_ic_md_numbered, R.string.square_md_number_list) { toggleBlock("1. ") },
            MdTool(R.drawable.square_ic_md_quote, R.string.square_md_quote) { toggleBlock("> ") },
            MdTool(R.drawable.square_ic_md_code, R.string.square_md_code) { insertCode() },
            MdTool(R.drawable.square_ic_md_link, R.string.square_md_link) { insertLink() },
            MdTool(R.drawable.square_ic_md_divider, R.string.square_md_divider) { insertDivider() }
        )
        val size = (36 * density).toInt()
        val pad = (7 * density).toInt()
        tools.forEach { tool ->
            binding.llMdTools.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                setBackgroundResource(R.drawable.bg_square_md_tool)
                setPadding(pad, pad, pad, pad)
                setImageResource(tool.icon)
                contentDescription = getString(tool.desc)
                // 不抢焦点，避免点工具时收起键盘
                isFocusable = false
                setOnClickListener { tool.action() }
            })
        }
        binding.tvMdPreview.setOnClickListener { openPreview() }
    }

    /**
     * 行内样式：包裹/取消包裹选区；无选区时插入标记并把光标放中间
     */
    private fun wrapInline(prefix: String, suffix: String) {
        val et = binding.etContent
        val text = et.text
        val start = minOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
        val end = maxOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
        if (end > start) {
            val wrapped = start >= prefix.length && end + suffix.length <= text.length &&
                    text.subSequence(start - prefix.length, start).toString() == prefix &&
                    text.subSequence(end, end + suffix.length).toString() == suffix
            if (wrapped) {
                text.delete(end, end + suffix.length)
                text.delete(start - prefix.length, start)
                et.setSelection(start - prefix.length, end - prefix.length)
            } else {
                text.insert(end, suffix)
                text.insert(start, prefix)
                et.setSelection(start + prefix.length, end + prefix.length)
            }
        } else {
            text.insert(start, prefix + suffix)
            et.setSelection(start + prefix.length)
        }
    }

    /**
     * 标题：`#` → `##` → `###` → 取消，循环切换（替换掉原有的列表/引用标记）
     */
    private fun toggleHeading() {
        val (start, end, line) = currentLine()
        val existing = blockPrefixPattern.find(line)?.value
        val body = line.removePrefix(existing ?: "")
        val target = when (existing) {
            "# " -> "## "
            "## " -> "### "
            "### " -> null
            else -> "# "
        }
        applyLine(start, end, (target ?: "") + body)
    }

    /**
     * 块级样式（无序/有序列表、引用）：同一标记再点取消，不同标记之间直接替换
     */
    private fun toggleBlock(prefix: String) {
        val (start, end, line) = currentLine()
        val existing = blockPrefixPattern.find(line)?.value
        val body = line.removePrefix(existing ?: "")
        val target = if (existing == prefix) null else prefix
        applyLine(start, end, (target ?: "") + body)
    }

    /**
     * 代码：无选区插入行内代码标记；单行选区包裹行内代码；多行选区包裹围栏代码块
     */
    private fun insertCode() {
        val et = binding.etContent
        val text = et.text
        val start = minOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
        val end = maxOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
        when {
            end == start -> {
                text.insert(start, "``")
                et.setSelection(start + 1)
            }
            !text.subSequence(start, end).contains('\n') -> wrapInline("`", "`")
            else -> {
                val selection = text.subSequence(start, end).toString()
                val pre = if (start > 0 && text[start - 1] != '\n') "\n" else ""
                val post = if (end < text.length && text[end] != '\n') "\n" else ""
                text.replace(start, end, "$pre```\n$selection\n```$post")
                val contentStart = start + pre.length + 4
                et.setSelection(contentStart, contentStart + selection.length)
            }
        }
    }

    /**
     * 链接：`[文字](链接)`；有选区时选区作为文字，插入后选中链接占位方便直接输入
     */
    private fun insertLink() {
        val et = binding.etContent
        val text = et.text
        val start = minOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
        val end = maxOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
        val label = if (end > start) {
            text.subSequence(start, end).toString()
        } else {
            getString(R.string.square_md_link)
        }
        val url = "https://"
        val markdown = "[$label]($url)"
        text.replace(start, end, markdown)
        val urlStart = start + label.length + 3
        et.setSelection(urlStart, urlStart + url.length)
    }

    /**
     * 分割线：单独一行插入 `---`
     */
    private fun insertDivider() {
        val et = binding.etContent
        val text = et.text
        val cursor = et.selectionStart.coerceIn(0, text.length)
        val needLeading = cursor > 0 && text[cursor - 1] != '\n'
        val insert = (if (needLeading) "\n" else "") + "---\n"
        text.insert(cursor, insert)
        et.setSelection(cursor + insert.length)
    }

    /** 当前光标所在行的范围与内容 */
    private fun currentLine(): Triple<Int, Int, String> {
        val et = binding.etContent
        val text = et.text
        var start = et.selectionStart.coerceIn(0, text.length)
        while (start > 0 && text[start - 1] != '\n') start--
        var end = start
        while (end < text.length && text[end] != '\n') end++
        return Triple(start, end, text.subSequence(start, end).toString())
    }

    /** 整行替换并把光标移到行尾 */
    private fun applyLine(start: Int, end: Int, newLine: String) {
        val et = binding.etContent
        et.text.replace(start, end, newLine)
        val cursor = (start + newLine.length).coerceAtMost(et.text.length)
        et.setSelection(cursor)
    }

    /**
     * 预览即将发布的动态：Markdown 正文按正式发布的方式渲染 + 图片九宫格
     *
     * 只读展示，不影响编辑内容；未填写正文时提示先输入（与发布校验一致）。
     */
    private fun openPreview() {
        val content = binding.etContent.text.toString().trim()
        if (content.isEmpty()) {
            toast(R.string.square_content_empty)
            return
        }
        startActivity(
            SquarePublishPreviewActivity.intent(this, content, existingUrls + selected)
        )
    }
}
