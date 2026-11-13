package com.fzm.chat.biz.webview

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.biz.R
import com.fzm.chat.biz.utils.ImageUtils
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.bean.PreSendParams
import com.fzm.chat.core.data.po.MessageContent
import com.fzm.chat.router.main.MainModule
import com.permissionx.guolindev.PermissionX
import com.zjy.architecture.ext.toast
import dtalk.biz.Biz
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.TreeMap

/**
 * 谈信 App × H5 通用宿主对象 `window.__tanxinHost`（对接文档《谈信 App × H5 通用对接说明》）。
 *
 * 约定（与文档一致）：
 * - 全局对象只认 `__tanxinHost`，幂等注入，**只补方法不整体覆盖**（保留别的页面写入的方法）；
 * - 注入时机：Android `onPageStarted`，并在 `onPageCommitVisible` / `onPageFinished` 兜底；
 * - 桥接方法同步、不返回 Promise、不抛异常；
 * - 能力自报 `actions`，未实现的能力不暴露（当前实现：openChat / back / topInset / share / shareSquare / saveImage）；
 * - 分享载荷按文档 §4 校验：title 必填 ≤120；description（兼容 `summary`）≤300；iconUrl http/https ≤2048；
 *   url 必填 https ≤2048；source ≤60，不合规的卡片直接丢弃；
 * - 兼容别名 `shareCard` / `shareToChat` / `shareToSquare`（与 `share` / `shareSquare` 同一条链路）。
 *
 * 原生桥对象名 `TanxinNative`（H5 不需要直接调用，仅供 `__tanxinHost` 内部使用）。
 */
object TanxinHost {

    private const val TAG = "TanxinHost"

    /** H5 宿主对象名（逐字，所有项目统一） */
    const val HOST_NAME = "__tanxinHost"

    /** 原生桥对象名（内部使用） */
    const val NATIVE_NAME = "TanxinNative"

    /** 宿主平台名（逐字，文档要求） */
    const val PLATFORM = "tanxin-android"

    /** 分享图片到广场时，传给发帖页的图片本地路径 extra（与 PublishSquareActivity 约定） */
    const val EXTRA_SHARE_IMAGE = "shareImagePath"

    /** 单张图片上限（分片累计），防止异常页面把内存打满 */
    private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024L

    /** 分享载荷里 imageBase64 的字符上限（约 12MB 原始字节，base64 膨胀 4/3） */
    private const val MAX_SHARE_IMAGE_CHARS = 16 * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 安装宿主桥（每个 WebView 只需一次，必须在 `loadUrl` 之前调用）。
     *
     * @param activityProvider 需要 Activity 的能力（保存图片运行时权限）用；可空
     * @param onBack 页面调用 `back()` 时优先交给宿主处理；返回 true 表示已处理
     */
    @JvmOverloads
    fun install(
        webView: WebView,
        activityProvider: (() -> FragmentActivity?)? = null,
        onBack: (() -> Boolean)? = null
    ) {
        try {
            val bridge = TanxinNativeBridge(webView, activityProvider, onBack)
            webView.addJavascriptInterface(bridge, NATIVE_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "install failed: ${e.javaClass.simpleName}")
        }
    }

    /** 注入（幂等）：在页面脚本执行前后都可调用，重复执行只补齐方法 */
    fun inject(webView: WebView) {
        try {
            webView.evaluateJavascript(script(topInsetOf(webView)), null)
        } catch (e: Exception) {
            Log.w(TAG, "inject failed: ${e.javaClass.simpleName}")
        }
    }

    /** 宿主对象脚本（HTML 预注入与运行时注入共用） */
    fun script(topInset: Int): String {
        return """
            (function () {
              try {
                var host = window.$HOST_NAME;
                if (!host || typeof host !== 'object') {
                  host = {};
                  window.$HOST_NAME = host;
                }
                host.name = '$PLATFORM';
                host.topInset = $topInset;
                function payloadJson(payload) {
                  try {
                    if (typeof payload === 'string') return payload;
                    return JSON.stringify(payload || {});
                  } catch (e) {
                    return '{}';
                  }
                }
                host.openChat = function (address) {
                  try {
                    window.$NATIVE_NAME.openChat(String(address === undefined || address === null ? '' : address));
                  } catch (e) {}
                };
                host.back = function () {
                  try { window.$NATIVE_NAME.back(); } catch (e) {}
                };
                host.share = function (payload) {
                  try { window.$NATIVE_NAME.share(payloadJson(payload)); } catch (e) {}
                };
                host.shareSquare = function (payload) {
                  try { window.$NATIVE_NAME.shareSquare(payloadJson(payload)); } catch (e) {}
                };
                // 兼容别名：其它项目/旧页面可能按这些名字探测，指向同一条通道（不新增第二条链路）
                host.shareCard = host.share;
                host.shareToChat = host.share;
                host.shareToSquare = host.shareSquare;
                // 直接把一张图分享出去（与「保存图片」用的是同一份 base64）
                host.shareImage = function (base64, filename) {
                  try {
                    window.$NATIVE_NAME.shareImage(String(base64 || ''), String(filename || ''));
                  } catch (e) {}
                };
                host.shareSquareImage = function (base64, filename) {
                  try {
                    window.$NATIVE_NAME.shareSquareImage(String(base64 || ''), String(filename || ''));
                  } catch (e) {}
                };
                host.saveImage = function (base64, filename) {
                  try {
                    window.$NATIVE_NAME.saveImage(String(base64 || ''), String(filename || ''));
                  } catch (e) {}
                };
                host.saveImageBegin = function (filename, totalBytes) {
                  try {
                    window.$NATIVE_NAME.saveImageBegin(String(filename || ''), String(totalBytes || 0));
                  } catch (e) {}
                };
                host.saveImageChunk = function (seq, base64Chunk) {
                  try {
                    window.$NATIVE_NAME.saveImageChunk(String(seq || 0), String(base64Chunk || ''));
                  } catch (e) {}
                };
                host.saveImageEnd = function () {
                  try { window.$NATIVE_NAME.saveImageEnd(); } catch (e) {}
                };
                host.actions = ['openChat', 'back', 'share', 'shareCard', 'shareToChat',
                               'shareSquare', 'shareToSquare', 'shareImage', 'shareSquareImage',
                               'saveImage'];
              } catch (e) {}
            })();
        """.trimIndent()
    }

    /** WebView 顶部被状态栏/刘海覆盖的像素数（页面据此让开安全区） */
    fun topInsetOf(webView: WebView): Int {
        return try {
            val activity = webView.context.findActivity() ?: return 0
            val decor = activity.window.decorView
            val location = IntArray(2)
            webView.getLocationInWindow(location)
            val decorLocation = IntArray(2)
            decor.getLocationInWindow(decorLocation)
            val top = location[1] - decorLocation[1]
            var inset = (statusBarHeight(activity) - top).coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val safeInset = decor.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0
                inset = maxOf(inset, (safeInset - top).coerceAtLeast(0))
            }
            inset
        } catch (e: Exception) {
            0
        }
    }

    private fun statusBarHeight(context: Context): Int {
        return try {
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        } catch (e: Exception) {
            0
        }
    }

    private fun Context.findActivity(): FragmentActivity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is FragmentActivity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /** `0x…`（链地址）→ 谈信账号地址；`1…` 原样返回；查不到返回 null */
    private suspend fun resolveAccountAddress(raw: String): String? {
        if (!raw.lowercase(Locale.ROOT).startsWith("0x")) return raw
        return try {
            val users = ChatDatabaseProvider.provide().friendUserDao().getAllUsersSuspend()
            users.firstOrNull { po ->
                po.address.equals(raw, ignoreCase = true) ||
                        po.chainAddress.values.any { it.equals(raw, ignoreCase = true) }
            }?.address
        } catch (e: Exception) {
            Log.w(TAG, "resolve address failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * 分享载荷（文档 §4 字段：title / description / iconUrl / url / source）
     */
    private class SharePayload(
        val title: String,
        val description: String,
        val iconUrl: String,
        val url: String,
        val source: String,
        /** 分享图片（base64，可选）：H5 传了就按图片分享，不传按文本卡片分享 */
        val imageBase64: String
    )

    /**
     * 解析并校验 H5 分享载荷：
     * - title 必填，≤120；
     * - url 必填，必须 https、≤2048；
     * - description ≤300（兼容其它项目的 `summary` 字段）；iconUrl 选填 http/https ≤2048；source ≤60。
     *
     * 缺 `title` / `url` 或不合规时返回 null（文档：这样的卡片不要发）。
     */
    private fun parseSharePayload(payload: String?): SharePayload? {
        if (payload.isNullOrBlank()) return null
        val json = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return null
        }
        // title 超长按文档上限截断（不整张丢弃）；url 截断会坏链，超长直接视为不合规
        val title = json.optString("title").trim().take(120)
        val url = json.optString("url").trim()
        if (title.isEmpty()) return null
        if (url.isEmpty() || url.length > 2048 || !url.startsWith("https://", ignoreCase = true)) {
            return null
        }
        val description = json.optString("description").trim()
            .ifEmpty { json.optString("summary").trim() }
        val iconUrl = json.optString("iconUrl").trim().let {
            if (it.length <= 2048 &&
                (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true))
            ) it else ""
        }
        return SharePayload(
            title = title,
            description = description.take(300),
            iconUrl = iconUrl,
            url = url,
            source = json.optString("source").trim().take(60),
            // 兼容 imageBase64 / image 两种写法；data:image/...;base64, 前缀由 decodeBase64 处理
            imageBase64 = json.optString("imageBase64").trim()
                .ifEmpty { json.optString("image").trim() }
                .take(MAX_SHARE_IMAGE_CHARS)
        )
    }

    private fun decodeBase64(raw: String?): ByteArray? {
        if (raw.isNullOrEmpty()) return null
        val data = if (raw.startsWith("data:", ignoreCase = true)) {
            raw.substringAfter("base64,", "")
        } else {
            raw
        }
        if (data.isEmpty()) return null
        return try {
            // 兼容 URL-safe base64（`-` / `_`）
            val flags = if (data.contains('-') || data.contains('_')) {
                Base64.URL_SAFE or Base64.NO_WRAP
            } else {
                Base64.DEFAULT
            }
            Base64.decode(data, flags)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 原生桥：H5 的 `window.__tanxinHost` 把这些调用转发到这里。
     * 所有方法都在 JavaBridge 线程被调用，UI/DB 操作必须切线程。
     */
    @Keep
    private class TanxinNativeBridge(
        private val webView: WebView,
        private val activityProvider: (() -> FragmentActivity?)?,
        private val onBack: (() -> Boolean)?
    ) {

        private val context: Context
            get() = webView.context

        /** 分片保存状态（互斥访问） */
        private val chunkLock = Any()
        private val chunks = TreeMap<Int, ByteArray>()
        private var chunkName: String = ""
        private var chunkTotal: Long = 0L
        private var chunkBytes: Long = 0L

        @JavascriptInterface
        fun openChat(address: String?) {
            val raw = address?.trim().orEmpty()
            // 文档：address 是谈信账号地址（0x…）或绑定的链地址（1…），其它输入一律忽略（不当网址/路由参数打开）
            if (raw.isEmpty() ||
                !(raw.startsWith("0x", ignoreCase = true) || raw.startsWith("1"))
            ) {
                return
            }
            TanxinHost.scope.launch {
                val resolved = TanxinHost.resolveAccountAddress(raw)
                webView.post {
                    if (resolved.isNullOrEmpty()) {
                        context.toast(R.string.biz_tanxin_host_user_not_found)
                        return@post
                    }
                    try {
                        val postcard = ARouter.getInstance().build(MainModule.CHAT)
                            .withInt("channelType", ChatConst.PRIVATE_CHANNEL)
                            .withString("address", resolved)
                        val activity = try {
                            activityProvider?.invoke()
                        } catch (e: Exception) {
                            null
                        }
                        if (activity != null) postcard.navigation(activity) else postcard.navigation()
                    } catch (e: Exception) {
                        Log.w(TanxinHost.TAG, "openChat failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }

        @JavascriptInterface
        fun back() {
            webView.post {
                val handled = try {
                    onBack?.invoke() ?: false
                } catch (e: Exception) {
                    false
                }
                if (handled) return@post
                try {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        activityProvider?.invoke()?.finish()
                    }
                } catch (e: Exception) {
                    Log.w(TanxinHost.TAG, "back failed: ${e.javaClass.simpleName}")
                }
            }
        }

        @JavascriptInterface
        fun share(payload: String?) {
            val card = TanxinHost.parseSharePayload(payload) ?: return
            // H5 传了 imageBase64：按图片消息分享（与「保存图片」用的是同一张图）
            if (card.imageBase64.isNotEmpty()) {
                val bytes = TanxinHost.decodeBase64(card.imageBase64)
                if (bytes != null && bytes.isNotEmpty()) {
                    shareImageMessage(bytes, "share_${System.currentTimeMillis()}.png")
                    return
                }
            }
            val text = buildString {
                append(card.title)
                if (card.description.isNotEmpty()) append("\n\n").append(card.description)
                append("\n\n").append(card.url)
            }
            webView.post {
                try {
                    val params = PreSendParams(
                        Biz.MsgType.Text_VALUE,
                        MessageContent.text(text),
                        null
                    )
                    navigateContactSelect(params)
                } catch (e: Exception) {
                    Log.w(TanxinHost.TAG, "share failed: ${e.javaClass.simpleName}")
                }
            }
        }

        @JavascriptInterface
        fun shareSquare(payload: String?) {
            val card = TanxinHost.parseSharePayload(payload) ?: return
            val imagePath = if (card.imageBase64.isNotEmpty()) {
                TanxinHost.decodeBase64(card.imageBase64)?.let {
                    saveShareImage(it, "square_${System.currentTimeMillis()}.png")
                }
            } else {
                null
            }
            webView.post {
                try {
                    // 打开广场发帖页并预填内容（用户确认后发布，避免 H5 静默代发）
                    val postcard = ARouter.getInstance().build(MainModule.SQUARE_SHARE)
                        .withString("shareTitle", card.title)
                        .withString("shareDescription", card.description)
                        .withString("shareUrl", card.url)
                        .withString("shareSource", card.source)
                        .withString("shareIconUrl", card.iconUrl)
                    if (!imagePath.isNullOrEmpty()) {
                        postcard.withString(TanxinHost.EXTRA_SHARE_IMAGE, imagePath)
                    }
                    val activity = try {
                        activityProvider?.invoke()
                    } catch (e: Exception) {
                        null
                    }
                    if (activity != null) postcard.navigation(activity) else postcard.navigation()
                } catch (e: Exception) {
                    Log.w(TanxinHost.TAG, "shareSquare failed: ${e.javaClass.simpleName}")
                }
            }
        }

        /** 直接分享一张图片到聊天（H5 传 base64） */
        @JavascriptInterface
        fun shareImage(base64: String?, filename: String?) {
            val bytes = TanxinHost.decodeBase64(base64) ?: return
            shareImageMessage(bytes, filename)
        }

        /** 直接分享一张图片到广场发帖页（H5 传 base64） */
        @JavascriptInterface
        fun shareSquareImage(base64: String?, filename: String?) {
            val bytes = TanxinHost.decodeBase64(base64) ?: return
            val path = saveShareImage(bytes, filename) ?: return
            webView.post {
                try {
                    val postcard = ARouter.getInstance().build(MainModule.SQUARE_SHARE)
                        .withString(TanxinHost.EXTRA_SHARE_IMAGE, path)
                    val activity = try {
                        activityProvider?.invoke()
                    } catch (e: Exception) {
                        null
                    }
                    if (activity != null) postcard.navigation(activity) else postcard.navigation()
                } catch (e: Exception) {
                    Log.w(TanxinHost.TAG, "shareSquareImage failed: ${e.javaClass.simpleName}")
                }
            }
        }

        /** 把本地图片作为图片消息发给聊天对象：先落到缓存目录，再进选择会话页 */
        private fun shareImageMessage(bytes: ByteArray, filename: String?) {
            val path = saveShareImage(bytes, filename) ?: run {
                webView.post { context.toast(R.string.biz_tips_image_save_fail) }
                return
            }
            val size = imageSizeOf(path)
            webView.post {
                try {
                    val params = PreSendParams(
                        Biz.MsgType.Image_VALUE,
                        MessageContent.image(null, path, size),
                        null
                    )
                    navigateContactSelect(params)
                } catch (e: Exception) {
                    Log.w(TanxinHost.TAG, "shareImage failed: ${e.javaClass.simpleName}")
                }
            }
        }

        private fun navigateContactSelect(params: PreSendParams) {
            val postcard = ARouter.getInstance().build(MainModule.CONTACT_SELECT)
                .withSerializable("preSend", params)
            val activity = try {
                activityProvider?.invoke()
            } catch (e: Exception) {
                null
            }
            if (activity != null) postcard.navigation(activity) else postcard.navigation()
        }

        /** 写入应用缓存目录（不占相册、不需要存储权限） */
        private fun saveShareImage(bytes: ByteArray, filename: String?): String? {
            if (bytes.isEmpty()) return null
            return try {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val name = filename?.takeIf { it.isNotBlank() } ?: "share_${System.currentTimeMillis()}.png"
                val file = File(dir, name)
                file.writeBytes(bytes)
                file.absolutePath
            } catch (e: Exception) {
                Log.w(TanxinHost.TAG, "saveShareImage failed: ${e.javaClass.simpleName}")
                null
            }
        }

        private fun imageSizeOf(path: String): IntArray {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            return intArrayOf(options.outWidth.coerceAtLeast(1), options.outHeight.coerceAtLeast(1))
        }

        @JavascriptInterface
        fun saveImage(base64: String?, filename: String?) {
            val bytes = TanxinHost.decodeBase64(base64) ?: return
            saveBytes(bytes, filename)
        }

        @JavascriptInterface
        fun saveImageBegin(filename: String?, totalBytes: String?) {
            synchronized(chunkLock) {
                chunks.clear()
                chunkName = filename?.takeIf { it.isNotBlank() } ?: "image.png"
                chunkTotal = totalBytes?.toLongOrNull() ?: 0L
                chunkBytes = 0L
            }
        }

        @JavascriptInterface
        fun saveImageChunk(seq: String?, base64Chunk: String?) {
            val index = seq?.toIntOrNull() ?: return
            val bytes = TanxinHost.decodeBase64(base64Chunk) ?: return
            synchronized(chunkLock) {
                if (chunks.isEmpty() && chunkName.isEmpty()) return
                if (chunkBytes + bytes.size > TanxinHost.MAX_IMAGE_BYTES) {
                    Log.w(TanxinHost.TAG, "saveImageChunk exceed limit, abort")
                    chunks.clear()
                    chunkName = ""
                    chunkBytes = 0L
                    return
                }
                chunks[index] = bytes
                chunkBytes += bytes.size
            }
        }

        @JavascriptInterface
        fun saveImageEnd() {
            val bytes: ByteArray
            val name: String
            var expectedTotal = 0L
            synchronized(chunkLock) {
                if (chunks.isEmpty()) {
                    chunkName = ""
                    chunkBytes = 0L
                    chunkTotal = 0L
                    return
                }
                val output = ByteArrayOutputStream()
                chunks.values.forEach { output.write(it) }
                bytes = output.toByteArray()
                name = chunkName
                expectedTotal = chunkTotal
                chunks.clear()
                chunkName = ""
                chunkBytes = 0L
                chunkTotal = 0L
            }
            if (expectedTotal > 0 && bytes.size.toLong() != expectedTotal) {
                Log.w(TanxinHost.TAG, "saveImageEnd size mismatch: ${bytes.size} != $expectedTotal")
            }
            saveBytes(bytes, name)
        }

        private fun saveBytes(bytes: ByteArray, filename: String?) {
            if (bytes.isEmpty()) return
            val name = filename?.takeIf { it.isNotBlank() } ?: "image.png"
            ensureStoragePermission {
                TanxinHost.scope.launch {
                    val uri = try {
                        ImageUtils.saveImageBytesToGallery(
                            context, bytes, name, "Tanxin"
                        )
                    } catch (e: Exception) {
                        Log.w(TanxinHost.TAG, "save image failed: ${e.javaClass.simpleName}")
                        null
                    }
                    webView.post {
                        if (uri != null) {
                            context.toast(R.string.biz_tips_image_save_success)
                        } else {
                            context.toast(R.string.biz_tips_image_save_fail)
                        }
                    }
                }
            }
        }

        private fun ensureStoragePermission(block: () -> Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                block()
                return
            }
            val activity = try {
                activityProvider?.invoke()
            } catch (e: Exception) {
                null
            }
            if (activity == null) {
                webView.post { context.toast(R.string.biz_tips_image_save_fail) }
                return
            }
            webView.post {
                PermissionX.init(activity)
                    .permissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    .request { allGranted, _, _ ->
                        if (allGranted) block() else context.toast(R.string.biz_tips_image_save_fail)
                    }
            }
        }

    }
}
