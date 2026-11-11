package com.fzm.chat.square

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fzm.chat.R
import com.fzm.chat.adapter.DialogOption
import com.fzm.chat.adapter.DialogOptionAdapter
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.databinding.ActivitySquareReportBinding
import com.fzm.chat.widget.BottomListDialog
import com.zjy.architecture.ext.dp
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.launch

/**
 * 举报帖子 / 公众号文章：整页表单（被举报内容 + 举报类型 + 举报理由选填 + 提交举报）
 *
 * 入口：广场列表 / 用户主页 item 的「举报」、详情页右上角「举报」（均针对他人的帖）。
 * 举报**不会**立刻藏帖，所以成功后不改变列表内容，只提示结果；重复举报 -20410 提示「已举报」。
 */
class SquareReportActivity : BizActivity() {

    companion object {
        private const val EXTRA_POST_ID = "postId"
        private const val EXTRA_CONTENT = "content"

        /** 举报理由最长字数（与服务端 detail 字段一致：最长 500 字） */
        const val MAX_DETAIL = 500

        /** 被举报内容预览最多字数（界面最多 3 行，超出部分不传，避免 Intent 传超大字符串） */
        private const val MAX_CONTENT = 300

        /** 举报类型：服务端 reason 取值 → 本地文案 */
        private val REASONS = listOf(
            SquareReportReason.SPAM to R.string.square_report_reason_spam,
            SquareReportReason.PORN to R.string.square_report_reason_porn,
            SquareReportReason.SCAM to R.string.square_report_reason_scam,
            SquareReportReason.ABUSE to R.string.square_report_reason_abuse,
            SquareReportReason.OTHER to R.string.square_report_reason_other
        )

        /**
         * @param post 被举报的帖子（取 postId 与正文作为「被举报内容」预览）
         */
        fun start(context: Context, post: SquarePost) {
            val postId = post.postId
            if (postId <= 0L) return
            context.startActivity(
                Intent(context, SquareReportActivity::class.java)
                    .putExtra(EXTRA_POST_ID, postId)
                    .putExtra(EXTRA_CONTENT, previewContent(post))
            )
        }

        /** 正文预览：官方帖先去掉 Markdown 标记，过长截断 */
        private fun previewContent(post: SquarePost): String {
            val raw = if (post.isMarkdown) {
                SquareMarkdown.toPlainText(post.content)
            } else {
                post.content.orEmpty()
            }
            return raw.trim().take(MAX_CONTENT)
        }
    }

    private val binding by init { ActivitySquareReportBinding.inflate(layoutInflater) }

    private val dialogAdapter by lazy { DialogOptionAdapter(this) }

    private var postId = 0L

    /** 选中的举报类型（null = 未选择，服务端 reason 取值） */
    private var reason: String? = null

    /** 提交中：避免连点重复提交 */
    private var submitting = false

    override val root: View
        get() = binding.root

    override fun initView() {
        postId = intent?.getLongExtra(EXTRA_POST_ID, 0L) ?: 0L
        if (postId <= 0L) {
            finish()
            return
        }
        binding.tvContent.text = intent?.getStringExtra(EXTRA_CONTENT).orEmpty()
        updateCounter(0)
    }

    override fun initData() {
        dialogAdapter.setData(
            REASONS.map { (value, textRes) ->
                DialogOption(getString(textRes)) { selectReason(value, getString(textRes)) }
            }.toMutableList()
        )
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
        binding.llType.setOnClickListener { showTypeDialog() }
        binding.etDetail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }

            override fun afterTextChanged(s: Editable?) {
                updateCounter(s?.length ?: 0)
            }
        })
        binding.btnSubmit.setOnClickListener { submit() }
    }

    /**
     * 举报类型选择弹窗（底部列表）
     */
    private fun showTypeDialog() {
        var dialog: BottomListDialog? = null
        dialog = BottomListDialog.create(this, dialogAdapter, 300.dp).apply {
            setTitle(getString(R.string.square_report_type))
            setCancelVisible(true)
            setOnItemClickListener { parent, _, position, _ ->
                (parent.getItemAtPosition(position) as? DialogOption)?.action?.invoke()
                dialog?.cancel()
            }
            show()
        }
    }

    private fun selectReason(value: String, label: String) {
        reason = value
        binding.tvType.text = label
        binding.tvType.setTextColor(ContextCompat.getColor(this, R.color.gray_33))
    }

    /** 理由字数计数（0/500） */
    private fun updateCounter(length: Int) {
        binding.tvCounter.text = getString(R.string.square_report_detail_counter, length, MAX_DETAIL)
    }

    private fun submit() {
        if (submitting) return
        val reason = reason
        if (reason == null) {
            toast(R.string.square_report_type_required)
            return
        }
        val detail = binding.etDetail.text?.toString()?.trim().orEmpty()
        submitting = true
        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.alpha = 0.6f
        binding.btnSubmit.text = getString(R.string.square_report_submitting)
        lifecycleScope.launch {
            val resp = SquareApi.reportPost(postId, reason, detail.ifEmpty { null })
            when {
                resp.isSuccess -> {
                    toast(R.string.square_report_success)
                    finish()
                }

                resp.result == SquareCode.REPORT_DUPLICATE -> {
                    toast(R.string.square_report_reported)
                    finish()
                }

                else -> {
                    toast(squareMessage(resp))
                    resetSubmit()
                }
            }
        }
    }

    private fun resetSubmit() {
        submitting = false
        binding.btnSubmit.isEnabled = true
        binding.btnSubmit.alpha = 1f
        binding.btnSubmit.text = getString(R.string.square_report_submit)
    }
}
