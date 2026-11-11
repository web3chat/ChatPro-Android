package com.fzm.chat.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.FragmentActivity
import com.fzm.chat.biz.webview.TanxinHost
import com.fzm.chat.core.BuildConfig
import com.fzm.chat.core.data.po.MessageContent
import com.fzm.chat.core.session.LoginDelegate
import com.permissionx.guolindev.PermissionX
import com.zjy.architecture.di.rootScope

/**
 * 通话会话：持有正在通话中的 WebView（H5 房间页面）。
 *
 * 支持在全屏通话页与悬浮小窗之间转移（WebView 复用，通话不中断）；
 * 页面侧按《App 内嵌 WebView 接入规范》注入 window.H5Wallet 钱包桥。
 */
object CallSession {

    private const val TAG = "CallSession"
    private const val BRIDGE_NAME = "H5WalletNative"

    /** 房间页挂断监听桥（H5 离开 #/room 路由时通知 App） */
    private const val ROOM_BRIDGE_NAME = "H5RoomNative"

    private val delegate by rootScope.inject<LoginDelegate>()

    /**
     * 当前通话的房间信息（邀请卡片内容；通话记录气泡无此信息）
     */
    @Volatile
    var invite: MessageContent? = null
        private set

    @Volatile
    var webView: WebView? = null
        private set

    val active: Boolean
        get() = webView != null

    /**
     * 通话页主文档加载失败（仅提示，不结束会话）
     */
    @Volatile
    var onError: ((String) -> Unit)? = null

    /**
     * 开始新的通话会话（会先结束已有会话）
     */
    fun start(context: Context, invite: MessageContent) {
        destroy()
        val url = withUserName(invite.callUrl) ?: return
        this.invite = invite
        CallOverlayManager.init(context)
        val view = createWebView(context.applicationContext)
        webView = view
        view.loadUrl(url)
    }

    /**
     * 房间链接追加当前用户昵称（H5 进房要求 name 必填，且每个参与者用自己的名字）。
     *
     * 链接形如 `...?room=..&pin=..&mode=voice#/`，参数必须加在 `#` 之前。
     */
    private fun withUserName(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw.contains("name=")) return raw
        val name = delegate.current.value?.nickname?.takeIf { it.isNotBlank() }
            ?: delegate.getAddress()
        if (name.isNullOrBlank()) return raw
        val hashIndex = raw.indexOf('#')
        val body = if (hashIndex >= 0) raw.substring(0, hashIndex) else raw
        val hash = if (hashIndex >= 0) raw.substring(hashIndex) else ""
        val separator = if (body.contains('?')) "&" else "?"
        return body + separator + "name=" + Uri.encode(name) + hash
    }

    /**
     * 结束通话：销毁 WebView 与页面连接
     */
    fun destroy() {
        val view = webView ?: return
        webView = null
        invite = null
        onError = null
        try {
            view.stopLoading()
            view.loadUrl("about:blank")
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.removeJavascriptInterface(ROOM_BRIDGE_NAME)
            view.removeJavascriptInterface(TanxinHost.NATIVE_NAME)
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 判断某一链接是否为当前通话房间
     */
    fun isCurrent(url: String?): Boolean {
        return active && !url.isNullOrEmpty() && invite?.callUrl == url
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun createWebView(context: Context): WebView {
        val view = WebView(context)
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // 不配置则进房"黑屏+无声"，必须点一次屏幕才出画面和声音
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        view.setBackgroundColor(Color.BLACK)
        // 进房身份校验：注入 window.H5Wallet（App 已登录，直接给钱包地址）
        view.addJavascriptInterface(WalletBridge(), BRIDGE_NAME)
        // 页面挂断监听：H5 点挂断会 replace 回 #/，但 location.search 仍带着 room/pin/name，
        // JoinView 会自动再次进房；这里检测到离开房间路由就直接结束通话并返回上一页
        view.addJavascriptInterface(RoomBridge(view), ROOM_BRIDGE_NAME)
        // 谈信 × H5 通用宿主对象（window.__tanxinHost）：通话页的返回=最小化
        TanxinHost.install(view, { CallOverlayManager.currentActivity as? FragmentActivity }) {
            val activity = CallOverlayManager.currentActivity
            if (activity != null) {
                activity.onBackPressed()
                true
            } else {
                false
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                // 摄像头/麦克风（WebRTC getUserMedia）：先确保运行时权限已授予，再放行给 H5
                val needVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val needAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!needVideo && !needAudio) {
                    request.grant(request.resources)
                    return
                }
                val activity = CallOverlayManager.currentActivity as? FragmentActivity
                if (activity == null) {
                    request.deny()
                    return
                }
                val permissions = mutableListOf<String>()
                if (needAudio) permissions.add(Manifest.permission.RECORD_AUDIO)
                if (needVideo) permissions.add(Manifest.permission.CAMERA)
                PermissionX.init(activity)
                    .permissions(permissions)
                    .request { allGranted, _, _ ->
                        if (allGranted) request.grant(request.resources) else request.deny()
                    }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "console: ${consoleMessage?.message()} " +
                            "@${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        view.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                view?.let {
                    injectWalletBridge(it)
                    injectRoomWatch(it)
                    TanxinHost.inject(it)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.let {
                    injectWalletBridge(it)
                    injectRoomWatch(it)
                    TanxinHost.inject(it)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "page error: ${error?.errorCode} ${error?.description}")
                    onError?.invoke(error?.description?.toString() ?: "")
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // 局域网自签名证书联调：仅放行通话服务自身域名的证书校验（见 CallConfig.TRUST_SELF_SIGNED）；
                // 正式环境请部署正式证书（App WebView 不接受自签名证书）
                val host = error?.url?.let { Uri.parse(it).host }
                if (CallConfig.TRUST_SELF_SIGNED && host == CallConfig.host()) {
                    handler?.proceed()
                } else {
                    handler?.cancel()
                }
            }
        }
        return view
    }

    /**
     * 注入页面约定的钱包桥（幂等，页面脚本或用户操作随时可用）：
     * window.H5Wallet.getAddress() -> Promise<0x地址>
     */
    private fun injectWalletBridge(view: WebView) {
        try {
            view.evaluateJavascript(BRIDGE_JS, null)
        } catch (e: Exception) {
            Log.w(TAG, "inject bridge failed: ${e.javaClass.simpleName}")
        }
    }

    private val BRIDGE_JS = """
        (function () {
          try {
            window.H5Wallet = window.H5Wallet || {
              getAddress: function () {
                try {
                  return Promise.resolve(window.$BRIDGE_NAME ? $BRIDGE_NAME.getAddress() : '');
                } catch (e) {
                  return Promise.resolve('');
                }
              }
            };
          } catch (e) {}
        })();
    """.trimIndent()

    /**
     * 注入房间路由监听（幂等）：
     *
     * dtalk 的挂断按钮 = `disconnect()` 后 `router.replace("/")`，但真实 URL 上的
     * `?room=&pin=&name=` 不会消失，JoinView 会自动再次进房（表现为“挂断后又自动加入”）。
     * 这里在页面内监听路由变化：进过 `#/room` 后一旦离开，就通知 App 结束通话。
     */
    private fun injectRoomWatch(view: WebView) {
        try {
            view.evaluateJavascript(ROOM_WATCH_JS, null)
        } catch (e: Exception) {
            Log.w(TAG, "inject room watch failed: ${e.javaClass.simpleName}")
        }
    }

    private val ROOM_WATCH_JS = """
        (function () {
          try {
            if (window.__dtalkRoomWatch) return;
            window.__dtalkRoomWatch = true;
            var joined = false;
            function check() {
              try {
                var hash = String(location.hash || '');
                if (hash.indexOf('/room') >= 0) {
                  joined = true;
                } else if (joined) {
                  joined = false;
                  try { window.$ROOM_BRIDGE_NAME.onHangup(); } catch (e) {}
                }
              } catch (e) {}
            }
            function wrap(name) {
              try {
                var origin = history[name];
                if (typeof origin === 'function') {
                  history[name] = function () {
                    var ret = origin.apply(this, arguments);
                    setTimeout(check, 0);
                    return ret;
                  };
                }
              } catch (e) {}
            }
            wrap('pushState');
            wrap('replaceState');
            window.addEventListener('popstate', check);
            window.addEventListener('hashchange', check);
            setInterval(check, 500);
            check();
          } catch (e) {}
        })();
    """.trimIndent()

    /**
     * 原生桥：H5 离开房间路由（点挂断 / 错误页返回）时结束通话
     */
    private class RoomBridge(private val webView: WebView) {
        @JavascriptInterface
        fun onHangup() {
            webView.post {
                Log.i(CallSession.TAG, "page hangup detected")
                val callActivity = CallOverlayManager.currentActivity as? CallActivity
                CallSession.destroy()
                CallOverlayManager.hide()
                if (callActivity != null && !callActivity.isFinishing) {
                    callActivity.finish()
                }
            }
        }
    }

    /**
     * 原生桥：返回当前登录账号的钱包地址
     */
    private class WalletBridge {
        @JavascriptInterface
        fun getAddress(): String = CallSession.delegate.getAddress() ?: ""
    }
}
