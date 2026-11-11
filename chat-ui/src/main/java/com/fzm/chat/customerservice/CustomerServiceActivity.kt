package com.fzm.chat.customerservice

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.alibaba.android.arouter.facade.annotation.Route
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.webview.TanxinHost
import com.fzm.chat.core.BuildConfig
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.databinding.ActivityCustomerServiceBinding
import com.fzm.chat.router.main.MainModule
import com.permissionx.guolindev.PermissionX
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koin.android.ext.android.inject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AI 客服（BTY 智能客服 H5：https://aikf.bityuan.com）
 *
 * 按《谈信 × BTY 智能客服 —— App 对接说明》（2026-09-24 版）实现：
 * - 惰性登录（方式 A，文档 §3.1）：**页面脚本之前**注入 `window.__csAuthProvider`
 *   （getAddress 纯本地读；getAuth 内部跑 nonce → 签名 → login，随时可被再次调用、每次当场新签，文档 §3.6）
 * - 注入时机：拦截主文档 HTML（[WebViewClient.shouldInterceptRequest]）把桥接 <script> 插到 <head> 之后，
 *   与页面 bundle 的执行时序无关；拉取失败退回运行时注入（幂等，不覆盖已建立的回填通道）
 * - 顶栏交接（§4）：注入 `window.__csHost = { name: 'tanxin-app', back }`（URL 同时带 `?cs_host=tanxin-app` 兜底），
 *   App 不再自带原生顶栏，页面上只有一条顶栏（返回 / 标题 / 会话历史）
 * - 原生返回（§4.3）：页面顶栏「返回」→ `CsAuthNative.goBack()`，与 App 物理返回同一结果（都退出客服页，§4.5-2）
 * - WebView 使用持久化存储（不清 cache/storage），保证页面本地会话可复用（§3.1 前提）
 * - 登录失败/断网不卡住页面：照常打开，页面会显示自己的登录页（§7）
 */
@Route(path = MainModule.CUSTOMER_SERVICE)
class CustomerServiceActivity : BizActivity() {

    companion object {
        private const val TAG = "CustomerService"
        private const val TRUST_HOST = "aikf.bityuan.com"
        private const val FILE_CHOOSER_CODE = 1001
        private const val BRIDGE_NAME = "CsAuthNative"

        /** 注入的 <script> id（仅用于识别注入结果） */
        private const val BRIDGE_SCRIPT_ID = "cs-bridge"

        /** 客服站点（附带 cs_host 让页面切换为 App 内形态；凭证走 JS 注入，不进 URL） */
        private val PAGE_URL = TanxinCsAuth.SITE + "/?cs_host=tanxin-app"

        /**
         * 页面脚本之前注入的桥接脚本（幂等，HTML 注入与运行时注入共用）：
         * - `__csAuthProvider`：惰性凭证提供者（文档 §3.1 方式 A）
         * - `getAuth()` 可被反复调用：回填通道用一个 FIFO 队列，不丢失并发发起的 Promise；
         *   App 侧每次都当场新签（文档 §3.6 / §8-3）
         * - `__csHost`：App 内形态标记 + 原生返回通道（文档 §4.2 / §4.3）；
         *   back 不吞错 —— 页面靠“是否抛错”判断通道可用性，吞错会被误判为“已处理”
         */
        private val BRIDGE_JS = """
            (function () {
              try {
                window.__csAuthResolvers = window.__csAuthResolvers || [];
                if (typeof window.__csAuthResolve !== 'function') {
                  window.__csAuthResolve = function (auth) {
                    var next = window.__csAuthResolvers.shift();
                    if (next) {
                      var payload = null;
                      try { payload = auth ? { auth: auth, tab_id: CsAuthNative.tabId() } : null; }
                      catch (e) { payload = auth ? { auth: auth, tab_id: '' } : null; }
                      next(payload);
                    }
                  };
                }
                window.__csAuthProvider = {
                  getAddress: function () {
                    try { return Promise.resolve((window.CsAuthNative && CsAuthNative.currentAddress()) || ''); }
                    catch (e) { return Promise.resolve(''); }
                  },
                  getAuth: function () {
                    return new Promise(function (resolve) {
                      window.__csAuthResolvers.push(resolve);
                      try { window.CsAuthNative.requestAuth(); }
                      catch (e) {
                        var i = window.__csAuthResolvers.indexOf(resolve);
                        if (i >= 0) { window.__csAuthResolvers.splice(i, 1); }
                        resolve(null);
                      }
                    });
                  }
                };
                window.__csHost = {
                  name: 'tanxin-app',
                  back: function () { window.CsAuthNative.goBack(); }
                };
              } catch (e) {}
            })();
        """.trimIndent()

        /** 主文档拉取（注入 HTML 用）：独立于登录 HTTP，超时更宽松 */
        private val docClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    private val delegate by inject<LoginDelegate>()

    private val binding by lazy { ActivityCustomerServiceBinding.inflate(layoutInflater) }

    override val root: View
        get() = binding.root

    /** WebView 引用（后台线程回填凭证时避免触碰未初始化的 binding） */
    private var webView: WebView? = null

    /** 页面 getAuth 可能被反复调用（文档 §3.6）：单线程串行执行，避免并发覆盖回填通道 */
    private val authExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cs-auth")
    }

    /** Activity 已销毁标记（销毁后不再向页面回填） */
    @Volatile
    private var destroyed = false

    /** 当前页面地址（用于限制桥接对象只在客服站点可用） */
    private var currentUrl: String? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun initView() {
        val webView = binding.webCs
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            blockNetworkImage = false
            setSupportMultipleWindows(false)
            // LiveKit 语音/视频自动播放
            mediaPlaybackRequiresUserGesture = false
        }
        webView.setBackgroundColor(0xFFFFFFFF.toInt())
        this.webView = webView
        // 先挂好 JS 桥接对象（页面随时可能调用；凭证回复见 CsAuthNative.requestAuth）
        webView.addJavascriptInterface(CsAuthNative(), BRIDGE_NAME)
        // 谈信 × H5 通用宿主对象（window.__tanxinHost）；页面返回 = 退出客服页（与物理返回一致）
        TanxinHost.install(webView, { this }) {
            exitCustomerService()
            true
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressCs.progress = newProgress
                binding.progressCs.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                uploadMsg: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = uploadMsg
                return try {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    startActivityForResult(
                        Intent.createChooser(intent, null), FILE_CHOOSER_CODE
                    )
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                // 摄像头/麦克风（WebRTC getUserMedia）：先确保运行时权限已授予，再放行给 H5
                val needMedia = request.resources.any {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (needMedia) {
                    PermissionX.init(this@CustomerServiceActivity)
                        .permissions(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                        .request { allGranted, _, _ ->
                            if (allGranted) request.grant(request.resources) else request.deny()
                        }
                } else {
                    request.grant(request.resources)
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                // Debug 下把页面 console 转到 logcat（页面检测到桥缺失时会 console.warn 报告通道名）
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "console: ${consoleMessage?.message()} " +
                                "@${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}"
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        webView.webViewClient = object : WebViewClient() {

            /**
             * 关键（文档 §3.1「页面脚本之前注入」）：把桥接 <script> 插到主文档 HTML 的 <head> 之后，
             * 与页面 bundle 的执行时序无关；拉取失败 / 非文档请求一律返回 null，
             * 交还给 WebView 正常加载（失败降级，文档 §7）。
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return try {
                    val accept = request.requestHeaders["Accept"] ?: ""
                    if (!request.isForMainFrame || request.method != "GET" ||
                        !isTrustedUrl(request.url.toString()) ||
                        !accept.contains("text/html", ignoreCase = true)
                    ) {
                        super.shouldInterceptRequest(view, request)
                    } else {
                        val html = fetchDocumentHtml(request)
                        if (html == null) {
                            super.shouldInterceptRequest(view, request)
                        } else {
                            Log.d(TAG, "inject bridge into document before page scripts")
                            WebResourceResponse(
                                "text/html", "utf-8", 200, "OK",
                                mutableMapOf("content-type" to "text/html; charset=utf-8"),
                                ByteArrayInputStream(
                                    injectBridgeIntoHtml(html).toByteArray(Charsets.UTF_8)
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "shouldInterceptRequest failed: ${e.javaClass.simpleName}")
                    null
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url
                // 桥接对象只对客服站点开放：非信任域移除，回到信任域再加回
                if (isTrustedUrl(url)) {
                    view.addJavascriptInterface(CsAuthNative(), BRIDGE_NAME)
                } else {
                    view.removeJavascriptInterface(BRIDGE_NAME)
                }
                injectBridge(view, url)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                // 兜底注入：HTML 拦截成功时为空操作（不覆盖已建立的回填队列）
                injectBridge(view, url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                injectBridge(view, url)
                probeBridge()
            }
        }
        // 照常打开页面：登录失败/断网也进入页面（页面会显示登录页，文档 §7）
        webView.loadUrl(PAGE_URL)
    }

    override fun initData() {
    }

    override fun setEvent() {
    }

    /** 运行时机兜底：把桥接脚本注入当前文档（幂等，不覆盖已有的回填队列） */
    private fun injectBridge(view: WebView, url: String?) {
        if (!isTrustedUrl(url)) return
        try {
            view.evaluateJavascript(BRIDGE_JS, null)
            TanxinHost.inject(view)
        } catch (e: Exception) {
            Log.w(TAG, "injectBridge failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 主文档拉取（同步执行于 WebView 的工作线程，允许阻塞）；失败返回 null。
     * 需自管请求头不外传：Host / Connection / Accept-Encoding（OkHttp 自行处理压缩）/
     * 条件头（避免拿到 304）/ Content-*；其余（UA、Cookie、Accept-Language 等）原样透传。
     */
    private fun fetchDocumentHtml(request: WebResourceRequest): String? {
        return try {
            val builder = Request.Builder().url(request.url.toString()).get()
            request.requestHeaders.forEach { (name, value) ->
                when (name.lowercase()) {
                    "host", "connection", "accept-encoding", "content-length",
                    "content-type", "if-none-match", "if-modified-since", "range" -> Unit
                    else -> try {
                        builder.header(name, value)
                    } catch (e: Exception) {
                        // 单个非法头忽略
                    }
                }
            }
            docClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                String(body.bytes(), charset)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchDocumentHtml failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 把桥接 <script> 插到 <head> 之后（最早时机）；找不到 head 时退化为插到 <html> 后 / 文档最前 */
    private fun injectBridgeIntoHtml(html: String): String {
        val script = "<script id=\"$BRIDGE_SCRIPT_ID\">$BRIDGE_JS</script>" +
                "<script id=\"tanxin-host\">${TanxinHost.script(0)}</script>"
        val headAt = html.indexOf("<head", ignoreCase = true)
        if (headAt >= 0) {
            val tagEnd = html.indexOf('>', headAt)
            if (tagEnd >= 0) {
                return html.substring(0, tagEnd + 1) + script + html.substring(tagEnd + 1)
            }
        }
        val htmlAt = html.indexOf("<html", ignoreCase = true)
        if (htmlAt >= 0) {
            val tagEnd = html.indexOf('>', htmlAt)
            if (tagEnd >= 0) {
                return html.substring(0, tagEnd + 1) + "<head>" + script + "</head>" +
                        html.substring(tagEnd + 1)
            }
        }
        return script + html
    }

    /** Debug 诊断：确认三个桥接对象已就位（只查类型与会话有无，不打印任何凭证） */
    private fun probeBridge() {
        if (!BuildConfig.DEBUG) return
        try {
            webView?.evaluateJavascript(
                "(function(){try{return [typeof window.__csAuthProvider, " +
                        "typeof window.__csHost, typeof window.CsAuthNative, " +
                        "typeof window.__tanxinHost, " +
                        "((window.localStorage && localStorage.getItem('cs_auth')) ? 'session' : 'no-session')]" +
                        ".join(' / ');}catch(e){return 'probe-error';}})();"
            ) { value -> Log.d(TAG, "bridge probe: $value") }
        } catch (e: Exception) {
            Log.w(TAG, "probeBridge failed: ${e.javaClass.simpleName}")
        }
    }

    private fun isTrustedUrl(url: String?): Boolean {
        return url != null && url.contains(TRUST_HOST)
    }

    /**
     * App 物理返回 / 页面顶栏「返回」共用同一函数：退出客服页。
     *
     * 不用 canGoBack()→goBack()：页是不使用 history.pushState 的 SPA，
     * 回退只会命中无意义的中间历史（表现为“没反应”）；文档 §4.5-2 要求与 App 原生返回同一结果。
     */
    private fun exitCustomerService() {
        if (!isFinishing) finish()
    }

    override fun onBackPressed() {
        exitCustomerService()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_CODE) {
            val callback = filePathCallback ?: return
            filePathCallback = null
            callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        destroyed = true
        authExecutor.shutdownNow()
        try {
            webView?.removeJavascriptInterface(BRIDGE_NAME)
            webView?.stopLoading()
            webView?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy webView failed: ${e.javaClass.simpleName}")
        }
        super.onDestroy()
    }

    /**
     * 供页面调用的桥接对象（文档 §3.1 / §4.3）
     *
     * 注意：登录凭证只 resolve 给当前页面（`window.__csAuthResolve`）；
     * 非客服站点页面在 onPageStarted 时已被移除该桥接对象。
     */
    inner class CsAuthNative {

        /** 读取当前钱包地址：纯本地、不发任何 HTTP（文档要求 3 秒内返回） */
        @JavascriptInterface
        fun currentAddress(): String {
            if (!isTrustedUrl(currentUrl)) return ""
            return TanxinCsAuth.currentAddress(delegate.preference) ?: ""
        }

        /**
         * 页面确认需要登录 / 登录态失效自愈（文档 §3.6）时调用：
         * 串行跑到 nonce → 签名 → login 的全新一轮（不缓存），完成后回主线程 resolve。
         */
        @JavascriptInterface
        fun requestAuth() {
            if (destroyed) return
            if (!isTrustedUrl(currentUrl)) {
                resolveToPage(null)
                return
            }
            Log.d(TAG, "getAuth: signing fresh credentials")
            authExecutor.execute {
                if (destroyed) return@execute
                val json = try {
                    val preference = delegate.preference
                    TanxinCsAuth.login(preference, TanxinCsAuth.tabId(preference))
                } catch (e: Exception) {
                    Log.w(TAG, "requestAuth failed: ${e.javaClass.simpleName}")
                    null
                }
                if (!destroyed) resolveToPage(json)
            }
        }

        /** 本标签页标识（长期复用，文档 §5） */
        @JavascriptInterface
        fun tabId(): String = TanxinCsAuth.tabId(delegate.preference)

        /** 页面顶栏的「返回」→ 退出客服页（与 App 物理返回同一函数，文档 §4.3 通道 1/2） */
        @JavascriptInterface
        fun goBack() {
            webView?.post { exitCustomerService() }
        }

        /** 页面探测的别名（文档 §4.3 通道 2：goBack / back / closeWebView / close / finish） */
        @JavascriptInterface
        fun back() = goBack()

        @JavascriptInterface
        fun closeWebView() = goBack()

        @JavascriptInterface
        fun close() = goBack()

        @JavascriptInterface
        fun finish() = goBack()
    }

    /** 把登录结果（data 的 JSON 字符串，或 null）交给页面；凭证不打印日志（文档 §8-8） */
    private fun resolveToPage(json: String?) {
        val web = webView ?: return
        web.post {
            if (destroyed) return@post
            val arg = if (json != null) "JSON.parse(${JSONObject.quote(json)})" else "null"
            try {
                web.evaluateJavascript(
                    "window.__csAuthResolve && window.__csAuthResolve($arg);", null
                )
            } catch (e: Exception) {
                Log.w(TAG, "resolveToPage failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
