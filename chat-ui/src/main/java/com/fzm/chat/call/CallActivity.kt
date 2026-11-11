package com.fzm.chat.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.core.data.po.MessageContent
import com.fzm.chat.databinding.ActivityCallBinding
import com.zjy.architecture.ext.notchSupport
import com.zjy.architecture.ext.toast
import com.zjy.architecture.util.other.BarUtils

/**
 * 全屏通话页：内嵌 H5 房间页面（h5_room，LiveKit）
 *
 * - 返回键/最小化按钮：把通话收起为悬浮小窗（通话不中断，可继续聊天）
 * - 挂断按钮：结束通话并离开房间
 */
class CallActivity : BizActivity() {

    companion object {
        private const val EXTRA_INVITE = "call_invite"

        /**
         * 打开全屏通话页
         *
         * @param invite 要加入的房间信息；为 null 时恢复当前进行中的通话（从小窗展开）
         */
        fun open(context: Context, invite: MessageContent?) {
            val intent = Intent(context, CallActivity::class.java)
            invite?.let { intent.putExtra(EXTRA_INVITE, it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private val binding by lazy { ActivityCallBinding.inflate(layoutInflater) }

    override val root: View
        get() = binding.root

    private var minimizedTipShown = false

    override fun setSystemBar() {
        notchSupport(window)
        BarUtils.setStatusBarColor(this, Color.BLACK, 0)
        BarUtils.setStatusBarLightMode(this, false)
        window?.navigationBarColor = Color.BLACK
    }

    override fun initView() {
        // 刘海屏/状态栏适配：本页是 LAYOUT_FULLSCREEN 的全屏 WebView，
        // 不给顶部留白时 H5 页面最顶端内容会被状态栏/刘海遮住
        applyTopInset()
        CallSession.onError = {
            toast(R.string.chat_call_webview_error)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        CallOverlayManager.init(this)

        val invite = intent.getSerializableExtra(EXTRA_INVITE) as? MessageContent
        if (!CallSession.active) {
            if (invite == null || invite.callUrl.isNullOrEmpty()) {
                toast(R.string.chat_call_create_failed)
                finish()
                return
            }
            CallSession.start(this, invite)
        } else if (invite != null && invite.callUrl != CallSession.invite?.callUrl) {
            // 切换房间（入口处已确认结束当前通话）
            CallSession.start(this, invite)
        }

        val web = CallOverlayManager.takeWebView()
        if (web == null) {
            finish()
            return
        }
        binding.flCallContainer.addView(
            web, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun initData() {
    }

    /**
     * 把状态栏 / 刘海的安全区高度加到根布局顶部内边距，避免 H5 顶端内容被遮挡。
     *
     * 用 systemBars + displayCutout 取两者最大值（API 28+ 才有刘海信息，低版本即状态栏高度）。
     */
    private fun applyTopInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val top = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            if (view.paddingTop != top) {
                view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun setEvent() {
        binding.ivCallMinimize.setOnClickListener { minimize() }
        binding.ivCallHangup.setOnClickListener { endCall() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // singleTop 复用：仅切房时重建会话
        val invite = intent?.getSerializableExtra(EXTRA_INVITE) as? MessageContent ?: return
        if (CallSession.active && invite.callUrl != CallSession.invite?.callUrl) {
            CallSession.start(this, invite)
            CallOverlayManager.takeWebView()?.let { web ->
                binding.flCallContainer.removeAllViews()
                binding.flCallContainer.addView(
                    web, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }
    }

    override fun onBackPressed() {
        minimize()
    }

    /**
     * 最小化为悬浮小窗（通话继续）
     */
    private fun minimize() {
        if (!CallSession.active) {
            finish()
            return
        }
        CallOverlayManager.minimize()
        if (!minimizedTipShown) {
            minimizedTipShown = true
            toast(R.string.chat_call_window_minimized_tips)
        }
        finish()
    }

    private fun endCall() {
        CallSession.destroy()
        CallOverlayManager.hide()
        finish()
    }
}
