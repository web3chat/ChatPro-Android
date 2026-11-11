package com.fzm.chat.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 崩溃兜底展示页（运行在独立进程 :crash）
 *
 * 主进程崩溃后由 [App] 注册的 UncaughtExceptionHandler 拉起本页，
 * 把崩溃堆栈展示出来（可复制），方便用户截图反馈，而不是直接闪退。
 *
 * 注意：本页不依赖任何 Koin/ARouter/网络初始化，[App] 在 :crash 进程会跳过全部 SDK 初始化。
 */
class CrashReportActivity : Activity() {

    companion object {
        const val EXTRA_CRASH = "extra_crash"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = intent?.getStringExtra(EXTRA_CRASH).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "应用遇到异常（请截图发给开发）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Color.parseColor("#D32F2F"))
            gravity = Gravity.CENTER
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val scroll = ScrollView(this)
        val content = TextView(this).apply {
            text = crash.ifEmpty { "（无堆栈信息）" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#333333"))
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(12))
        }
        scroll.addView(content)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val copyButton = Button(this).apply {
            text = "复制错误信息"
            setOnClickListener {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", crash))
                    Toast.makeText(this@CrashReportActivity, "已复制", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                }
            }
        }
        val closeButton = Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        }
        buttonRow.addView(copyButton)
        buttonRow.addView(closeButton)
        root.addView(
            buttonRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
