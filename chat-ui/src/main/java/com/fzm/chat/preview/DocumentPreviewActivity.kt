package com.fzm.chat.preview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.databinding.ActivityDocumentPreviewBinding
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.utils.DocumentTextExtractor
import com.fzm.chat.utils.FileOpenHelper
import com.zjy.architecture.ext.gone
import com.zjy.architecture.ext.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文档预览：聊天里收到的 Word / Excel / PPT / PDF / 文本直接在 App 内打开。
 *
 * - PDF：用系统 PdfRenderer 逐页渲染成图片
 * - docx / xlsx / pptx：解压 OOXML 提取文字（表格用制表符对齐）
 * - txt / csv / log / json / xml / md：按文本读取
 *
 * 老二进制格式（doc / xls / ppt）解析不了，交给右上角「用其他应用打开」。
 */
@Route(path = MainModule.DOC_PREVIEW)
class DocumentPreviewActivity : BizActivity() {

    @JvmField
    @Autowired
    var path: String? = null

    @JvmField
    @Autowired
    var name: String? = null

    private val binding by init { ActivityDocumentPreviewBinding.inflate(layoutInflater) }

    override val root: View
        get() = binding.root

    private val ext: String by lazy {
        val fromName = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromName.isNotEmpty()) fromName else path?.substringAfterLast('.', "")?.lowercase().orEmpty()
    }

    override fun initView() {
        ARouter.getInstance().inject(this)
        binding.ctbTitle.setTitle(name ?: getString(R.string.doc_preview_title))
        binding.ctbTitle.setOnLeftClickListener { finish() }
        binding.ctbTitle.setOnRightClickListener {
            // 需要高保真排版时，仍可交给 WPS / Office 等第三方应用
            FileOpenHelper.open(this, path, name)
        }
    }

    override fun initData() {
        if (path.isNullOrEmpty()) {
            showTips(getString(R.string.chat_tips_file_not_exist))
            return
        }
        when {
            ext == DocumentTextExtractor.PDF -> renderPdf()
            DocumentTextExtractor.isSupported(ext) -> renderText()
            else -> showTips(getString(R.string.chat_tips_unsupported_file_type))
        }
    }

    override fun setEvent() {
        // 事件在 initView 中绑定
    }

    // ------------------------------------------------------------ 文本类

    private fun renderText() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val file = File(path!!)
                if (!file.exists()) return@withContext null
                DocumentTextExtractor.extract(file, ext)
            }
            binding.pbLoading.gone()
            if (text.isNullOrBlank()) {
                showTips(getString(R.string.doc_preview_failed))
                return@launch
            }
            showText(text, monospace = ext == "xlsx" || ext == "csv")
        }
    }

    private fun showText(text: String, monospace: Boolean) {
        val tv = TextView(this).apply {
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(4f, 1f)
            if (monospace) typeface = Typeface.MONOSPACE
            setText(text)
        }
        binding.llContent.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        binding.svContent.visible()
    }

    // ------------------------------------------------------------ PDF

    private fun renderPdf() {
        lifecycleScope.launch {
            val pages = withContext(Dispatchers.IO) { renderPdfPages() }
            binding.pbLoading.gone()
            if (pages.isNullOrEmpty()) {
                showTips(getString(R.string.doc_preview_pdf_failed))
                return@launch
            }
            val width = resources.displayMetrics.widthPixels -
                    (resources.displayMetrics.density * 24).toInt()
            pages.forEach { bitmap ->
                val iv = ImageView(this@DocumentPreviewActivity).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val lp = LinearLayout.LayoutParams(
                    width.coerceAtLeast(1),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (12 * resources.displayMetrics.density).toInt()
                binding.llContent.addView(iv, lp)
            }
            binding.svContent.visible()
        }
    }

    /**
     * 逐页渲染 PDF，返回位图列表；最多渲染 [MAX_PDF_PAGES] 页，避免大文件 OOM。
     */
    private fun renderPdfPages(): List<Bitmap>? {
        val file = File(path!!)
        if (!file.exists()) return null
        val targetWidth = (resources.displayMetrics.widthPixels -
                (resources.displayMetrics.density * 24).toInt()).coerceAtLeast(320)
        val result = mutableListOf<Bitmap>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val count = minOf(renderer.pageCount, MAX_PDF_PAGES)
            for (i in 0 until count) {
                val page = renderer.openPage(i)
                try {
                    val scale = targetWidth.toFloat() / page.width
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    result.add(bitmap)
                } finally {
                    page.close()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DocPreview", "PDF 渲染失败: ${e.message}", e)
            return null
        } finally {
            try {
                renderer?.close()
            } catch (ignore: Exception) {
            }
            try {
                pfd?.close()
            } catch (ignore: Exception) {
            }
        }
        return result
    }

    // ------------------------------------------------------------ UI

    private fun showTips(msg: String) {
        binding.pbLoading.gone()
        binding.svContent.gone()
        binding.tvTips.text = msg
        binding.tvTips.gravity = Gravity.CENTER
        binding.tvTips.visible()
    }

    companion object {
        /** 大 PDF 只渲染前 30 页，剩下的提示用户用其他应用打开 */
        private const val MAX_PDF_PAGES = 30
    }
}
