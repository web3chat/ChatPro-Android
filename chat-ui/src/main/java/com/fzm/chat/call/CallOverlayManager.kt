package com.fzm.chat.call

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.fzm.chat.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 通话悬浮小窗管理（微信式最小化通话）：
 *
 * - 把通话 WebView 作为可拖拽、可缩放的迷你窗口叠加在 App 内任意页面上
 * - 拖动移动位置，拖右下角缩放尺寸，单击（或点展开按钮）回到全屏通话页
 * - 小窗跟随页面：Activity 切换后自动重新挂载到新页面
 */
object CallOverlayManager : Application.ActivityLifecycleCallbacks {

    @Volatile
    var currentActivity: Activity? = null
        private set

    @Volatile
    private var minimized = false

    private var miniView: FrameLayout? = null
    private var attachedActivity: Activity? = null

    /** 小窗位置与尺寸（px），跨页面记忆；-1 表示未初始化 */
    private var winX = -1
    private var winY = -1
    private var winW = 0
    private var winH = 0

    private var registered = false

    fun init(context: Context) {
        if (registered) return
        val application = context.applicationContext as? Application ?: return
        registered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    fun isShowing(): Boolean = miniView?.parent != null

    /**
     * 请求最小化：挂到当前（或下一个 resumed 的）非通话页 Activity 上
     */
    fun minimize() {
        if (CallSession.webView == null) return
        minimized = true
        val activity = currentActivity
        if (activity != null && activity !is CallActivity && !activity.isFinishing) {
            bind(activity)
        }
    }

    /**
     * 收起小窗（WebView 保留在会话中）
     */
    fun hide() {
        minimized = false
        release()
    }

    /**
     * 从悬浮窗取回 WebView（转交全屏通话页），并移除小窗
     */
    fun takeWebView(): android.webkit.WebView? {
        minimized = false
        val web = CallSession.webView
        (web?.parent as? ViewGroup)?.removeView(web)
        release()
        return web
    }

    private fun release() {
        miniView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        miniView = null
        attachedActivity = null
    }

    /**
     * 将小窗挂载到指定 Activity
     */
    private fun bind(activity: Activity) {
        val web = CallSession.webView ?: return release()
        if (!minimized) return
        if (attachedActivity === activity && miniView?.parent != null) return
        release()

        val decor = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val view = buildMiniView(activity)
        (web.parent as? ViewGroup)?.removeView(web)
        view.addView(web, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val isVideo = CallSession.invite?.callMode == "video"
        if (winW <= 0 || winH <= 0) {
            winW = dp(activity, if (isVideo) 160 else 132)
            winH = dp(activity, if (isVideo) 213 else 176)
        }
        val (parentW, parentH) = parentSize(activity, decor)
        if (winX < 0 || winY < 0) {
            // 默认靠近右下（略高于底部，避免遮住输入框）
            winX = parentW - winW - dp(activity, 12)
            winY = parentH * 2 / 3 - winH / 2
        }
        winX = winX.coerceIn(0, max(0, parentW - winW))
        winY = winY.coerceIn(0, max(0, parentH - winH))

        val lp = FrameLayout.LayoutParams(winW, winH)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = winX
        lp.topMargin = winY
        decor.addView(view, lp)
        attachedActivity = activity
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildMiniView(activity: Activity): FrameLayout {
        val view = FrameLayout(activity)
        view.clipToOutline = true
        view.elevation = dp(activity, 6).toFloat()
        view.background = ContextCompat.getDrawable(activity, R.drawable.bg_call_mini)

        // 触摸代理：拖动移动位置；单击展开全屏（最小化状态下不操作 H5 页面）
        val proxy = View(activity)
        proxy.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0
            private var startW = 0
            private var startH = 0
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = winX
                        startY = winY
                        startW = winW
                        startH = winH
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!moved && (abs(dx) > dp(activity, 4) || abs(dy) > dp(activity, 4))) {
                            moved = true
                        }
                        if (moved) {
                            moveWindow(activity, startX + dx.toInt(), startY + dy.toInt())
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!moved) {
                            expand(activity)
                        }
                        return true
                    }
                }
                return false
            }
        })
        view.addView(proxy, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 右上角：挂断
        val hangup = ImageView(activity)
        hangup.setImageResource(R.drawable.ic_call_hangup)
        hangup.background = ContextCompat.getDrawable(activity, R.drawable.bg_call_control_hangup)
        hangup.scaleType = ImageView.ScaleType.CENTER
        hangup.contentDescription = activity.getString(R.string.chat_call_window_hangup)
        hangup.setOnClickListener {
            CallLauncher.endCall()
        }
        val hangupLp = FrameLayout.LayoutParams(dp(activity, 30), dp(activity, 30))
        hangupLp.gravity = Gravity.TOP or Gravity.END
        hangupLp.topMargin = dp(activity, 6)
        hangupLp.rightMargin = dp(activity, 6)
        view.addView(hangup, hangupLp)

        // 展开回全屏
        val expandBtn = ImageView(activity)
        expandBtn.setImageResource(R.drawable.ic_call_expand)
        expandBtn.background = ContextCompat.getDrawable(activity, R.drawable.bg_call_control)
        expandBtn.scaleType = ImageView.ScaleType.CENTER
        expandBtn.contentDescription = activity.getString(R.string.chat_call_window_expand)
        expandBtn.setOnClickListener {
            expand(activity)
        }
        val expandLp = FrameLayout.LayoutParams(dp(activity, 30), dp(activity, 30))
        expandLp.gravity = Gravity.TOP or Gravity.END
        expandLp.topMargin = dp(activity, 6)
        expandLp.rightMargin = dp(activity, 6) + dp(activity, 34)
        view.addView(expandBtn, expandLp)

        // 右下角缩放柄
        val resize = ImageView(activity)
        resize.setImageResource(R.drawable.ic_call_resize)
        resize.alpha = 0.85f
        resize.contentDescription = activity.getString(R.string.chat_call_window_expand)
        resize.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startW = 0
            private var startH = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startW = winW
                        startH = winH
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        resizeWindow(
                            activity,
                            startW + (event.rawX - downRawX).toInt(),
                            startH + (event.rawY - downRawY).toInt()
                        )
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
        val resizeLp = FrameLayout.LayoutParams(dp(activity, 28), dp(activity, 28))
        resizeLp.gravity = Gravity.BOTTOM or Gravity.END
        view.addView(resize, resizeLp)

        miniView = view
        return view
    }

    private fun moveWindow(activity: Activity, x: Int, y: Int) {
        val view = miniView ?: return
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val parent = view.parent as? View ?: return
        val (parentW, parentH) = parentSize(activity, parent)
        winX = x.coerceIn(0, max(0, parentW - lp.width))
        winY = y.coerceIn(0, max(0, parentH - lp.height))
        lp.leftMargin = winX
        lp.topMargin = winY
        view.layoutParams = lp
    }

    private fun resizeWindow(activity: Activity, w: Int, h: Int) {
        val view = miniView ?: return
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val parent = view.parent as? View ?: return
        val (parentW, parentH) = parentSize(activity, parent)
        val minW = dp(activity, 104)
        val minH = dp(activity, 140)
        val maxW = min(parentW - winX - dp(activity, 8), dp(activity, 340)).coerceAtLeast(minW)
        val maxH = min(parentH - winY - dp(activity, 8), dp(activity, 480)).coerceAtLeast(minH)
        winW = w.coerceIn(minW, maxW)
        winH = h.coerceIn(minH, maxH)
        lp.width = winW
        lp.height = winH
        view.layoutParams = lp
    }

    private fun expand(activity: Activity) {
        if (activity.isFinishing) return
        CallActivity.open(activity, null)
    }

    /**
     * 父容器可用尺寸（未完成布局时退回屏幕尺寸）
     */
    private fun parentSize(activity: Activity, parent: View): Pair<Int, Int> {
        val metrics = activity.resources.displayMetrics
        val w = if (parent.width > 0) parent.width else metrics.widthPixels
        val h = if (parent.height > 0) parent.height else metrics.heightPixels
        return w to h
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    // ------------------------- Activity 生命周期 -------------------------

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        if (minimized && activity !is CallActivity && !activity.isFinishing) {
            bind(activity)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) {
            currentActivity = null
        }
        if (attachedActivity === activity) {
            // 宿主销毁：先摘除小窗，下个页面 resume 时重新挂载（WebView 保留）
            miniView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            attachedActivity = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
}
