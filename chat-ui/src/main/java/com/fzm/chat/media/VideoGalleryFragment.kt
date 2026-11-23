package com.fzm.chat.media

import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.launcher.ARouter
import xyz.doikki.videocontroller.StandardVideoController
import xyz.doikki.videocontroller.component.CompleteView
import xyz.doikki.videocontroller.component.ErrorView
import xyz.doikki.videocontroller.component.GestureView
import xyz.doikki.videocontroller.component.PrepareView
import xyz.doikki.videoplayer.player.VideoView
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizFragment
import com.fzm.chat.biz.utils.onExplain
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.data.model.*
import com.fzm.chat.core.data.po.MessageContent
import com.fzm.chat.core.data.po.MessageSource
import com.fzm.chat.core.data.po.getDisplayUrl
import com.fzm.chat.core.data.po.toFriendUser
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.utils.Utils
import com.fzm.chat.databinding.FragmentVideoGalleryBinding
import com.danikula.videocache.HttpProxyCacheServer
import com.fzm.chat.core.media.DownloadManager2
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.utils.ForwardHelper
import com.fzm.chat.widget.CustomControlView
import com.permissionx.guolindev.PermissionX
import com.zjy.architecture.data.Result
import com.zjy.architecture.ext.gone
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import com.zjy.architecture.ext.visible
import dtalk.biz.Biz
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import xyz.doikki.videoplayer.controller.BaseVideoController

/**
 * @author zhengjy
 * @since 2021/03/31
 * Description:
 */
class VideoGalleryFragment : BizFragment() {

    companion object {
        fun create(message: ChatMessage?, forwardPos: Int, url: String?, initPlay: Boolean, showGallery: Boolean, showOptions: Boolean = true): VideoGalleryFragment {
            return VideoGalleryFragment().apply {
                arguments = bundleOf(
                    "message" to message,
                    "forwardPos" to forwardPos,
                    "url" to url,
                    "initPlay" to initPlay,
                    "showGallery" to showGallery,
                    "showOptions" to showOptions,
                )
            }
        }
    }

    private var forward: ForwardMsg? = null
    private var message: ChatMessage? = null
    private var forwardPos: Int = -1
    private var url: String? = null
    private var startPlayWhenInit = false
    private var showGallery = true
    private var showOptions = true

    private var paused = false
    private var controller: BaseVideoController? = null

    /** 开始加载视频的时间，用于计算首帧/缓冲耗时 */
    private var playStartedAt = 0L

    /** 是否已经打过「加载结束」日志，避免重复 */
    private var loadLogged = false

    /** 视频加载大小展示：已加载字节 / 总大小 */
    private var sizeUrl: String? = null
    private var sizeTotalBytes = 0L
    private var sizeLocalExists = false
    private var sizeLastLoaded = -1L
    private var sizePollingJob: kotlinx.coroutines.Job? = null

    private val delegate by inject<LoginDelegate>()
    private val contactManager by inject<ContactManager>()

    /** 视频播放走本地代理缓存：远端地址首次边下边播，第二次直接读缓存 */
    private val proxy by inject<HttpProxyCacheServer>()

    private val binding by init<FragmentVideoGalleryBinding>()

    override val root: View
        get() = binding.root

    override fun initView(view: View, savedInstanceState: Bundle?) {
        message = arguments?.getSerializable("message") as? ChatMessage
        forwardPos = arguments?.getInt("forwardPos", -1) ?: -1
        url = arguments?.getString("url")
        startPlayWhenInit = arguments?.getBoolean("initPlay") ?: false
        showGallery = arguments?.getBoolean("showGallery") ?: true
        showOptions = arguments?.getBoolean("showOptions") ?: true
        if (forwardPos != -1) {
            forward = message?.msg?.forwardLogs?.filterMedia()?.get(forwardPos)
        }
        binding.preview.load(message?.msg?.getDisplayUrl(requireContext()))
        setupVideoPlayer(binding.videoPlayer)
    }

    override fun initData() {

    }

    override fun setEvent() {
        binding.flThumb.setOnClickListener { start() }
    }

    override fun onResume() {
        super.onResume()
        if (paused && !binding.videoPlayer.isPlaying) {
            start()
            paused = false
        }
    }

    private fun start() {
        // 不再等待整包下载：本地有就播本地，没有就流式播放远端地址
        if (binding.flThumb.isVisible) {
            binding.flThumb.gone()
        }
        if (binding.videoPlayer.isGone) {
            binding.videoPlayer.visible()
        }
        binding.videoPlayer.start()
        startVideoSizePolling()
    }

    // ==================== 视频加载大小展示 ====================

    private fun setupVideoSize(url: String?, localExists: Boolean, localSize: Long) {
        sizeUrl = url
        sizeLocalExists = localExists
        sizeTotalBytes = if (localExists) localSize else 0L
        sizeLastLoaded = -1L
        if (url.isNullOrEmpty()) {
            binding.tvVideoSize.gone()
            return
        }
        binding.tvVideoSize.visible()
        binding.tvVideoSize.bringToFront()
        binding.tvVideoSize.post { binding.tvVideoSize.bringToFront() }
        updateVideoSizeText(if (localExists) localSize else 0L)
        android.util.Log.d(
            "VideoFlow",
            "[size] visible=${binding.tvVideoSize.visibility} text=${binding.tvVideoSize.text} " +
                "local=$localExists size=$localSize"
        )
        if (!localExists) {
            // HEAD 拿远端总大小；失败则只显示已加载 KB
            lifecycleScope.launch {
                val length = fetchRemoteSize(url)
                if (length > 0) {
                    sizeTotalBytes = length
                    updateVideoSizeText(currentLoadedBytes())
                }
            }
        }
    }

    private fun startVideoSizePolling() {
        if (sizeUrl.isNullOrEmpty() || sizeLocalExists) return
        sizePollingJob?.cancel()
        sizePollingJob = lifecycleScope.launch {
            while (isActive) {
                val loaded = currentLoadedBytes()
                if (loaded != sizeLastLoaded) {
                    updateVideoSizeText(loaded)
                }
                if (sizeTotalBytes > 0 && loaded >= sizeTotalBytes) break
                kotlinx.coroutines.delay(500L)
            }
        }
    }

    private fun stopVideoSizePolling() {
        sizePollingJob?.cancel()
        sizePollingJob = null
    }

    private fun currentLoadedBytes(): Long {
        val url = sizeUrl ?: return 0L
        if (sizeLocalExists) return sizeTotalBytes
        return runCatching {
            // 下载中文件是 xxx.download，完成后才重命名为正式文件
            val full = proxy.getCacheFile(url)
            val temp = proxy.getTempCacheFile(url)
            when {
                full.exists() -> full.length()
                temp.exists() -> temp.length()
                else -> 0L
            }
        }.getOrDefault(0L)
    }

    private fun updateVideoSizeText(loaded: Long) {
        if (!isAdded) return
        sizeLastLoaded = loaded
        binding.tvVideoSize.text = if (sizeTotalBytes > 0) {
            getString(
                R.string.chat_video_size_loading,
                formatVideoSizeMb(loaded),
                formatVideoSizeMb(sizeTotalBytes)
            )
        } else {
            getString(R.string.chat_video_size_loading_unknown, formatVideoSizeMb(loaded))
        }
    }

    private fun formatVideoSizeMb(bytes: Long): String {
        return String.format(java.util.Locale.US, "%.1fM", bytes / 1024f / 1024f)
    }

    /** HEAD 请求获取远端视频 Content-Length */
    private suspend fun fetchRemoteSize(url: String): Long {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.instanceFollowRedirects = true
                conn.connect()
                val length = conn.contentLengthLong
                conn.disconnect()
                length
            }.getOrDefault(0L)
        }
    }

    override fun onPause() {
        super.onPause()
        stopVideoSizePolling()
        if (binding.videoPlayer.isPlaying) {
            binding.videoPlayer.pause()
            paused = true
        }
    }

    override fun onBackPressedSupport(): Boolean {
        controller?.gone()
        return super.onBackPressedSupport()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVideoSizePolling()
        binding.videoPlayer.release()
    }

    private fun setupVideoPlayer(player: VideoView<*>) {
        playStartedAt = android.os.SystemClock.elapsedRealtime()
        player.setOnStateChangeListener(object : VideoView.SimpleOnStateChangeListener() {
            override fun onPlayStateChanged(playState: Int) {
                val cost = android.os.SystemClock.elapsedRealtime() - playStartedAt
                when (playState) {
                    VideoView.STATE_PLAYING -> {
                        if (!loadLogged) {
                            loadLogged = true
                            android.util.Log.d(
                                "VideoFlow",
                                "[play] 加载结束：总耗时=${cost}ms 已缓冲=${player.bufferedPercentage}%"
                            )
                        }
                    }
                    VideoView.STATE_ERROR -> android.util.Log.e(
                        "VideoFlow",
                        "[play] 加载失败：cost=${cost}ms buffered=${player.bufferedPercentage}%"
                    )
                }
            }
        })
        val local = if (message != null && forwardPos == -1) {
            message!!.msg.localUrl
        } else {
            forward?.msg?.localUrl
        }
        val remote = if (message != null && forwardPos == -1) {
            message!!.msg.mediaUrl
        } else {
            url
        }
        val localFile = local?.let { java.io.File(it) }
        val localExists = localFile?.exists() == true
        // 进程被杀后重新进聊天：消息里的 localUrl 可能丢失，但代理缓存文件还在。
        // 已完整缓存时直接播缓存文件，避免重新从 0 加载。
        val remoteCachedFile = if (!localExists && !remote.isNullOrEmpty() &&
            runCatching { proxy.isCached(remote) }.getOrDefault(false)
        ) {
            runCatching { proxy.getCacheFile(remote) }.getOrNull()
        } else {
            null
        }
        val playLocalPath = when {
            localExists -> local
            remoteCachedFile?.exists() == true -> remoteCachedFile.absolutePath
            else -> null
        }
        val playLocalExists = !playLocalPath.isNullOrEmpty() &&
            java.io.File(playLocalPath).exists()
        val localSize = if (playLocalExists) {
            java.io.File(playLocalPath!!).length()
        } else 0L
        // 本地/缓存已存在就直接播本地；没有则用远端地址边下边播
        val playUrl = if (playLocalExists) {
            playLocalPath
        } else {
            remote ?: local
        }
        setupVideoSize(playUrl, playLocalExists, localSize)
        if (!playUrl.isNullOrEmpty()) {
            // 远端地址走代理缓存，避免每次打开都重新拉流
            val cached = runCatching { proxy.isCached(playUrl) }.getOrDefault(false)
            val realUrl = if (playUrl.startsWith("http", ignoreCase = true)) {
                runCatching { proxy.getProxyUrl(playUrl) }.getOrDefault(playUrl)
            } else {
                playUrl
            }
            player.setUrl(realUrl)
            android.util.Log.d(
                "VideoFlow",
                "[play] 开始加载视频：msgId=${message?.msgId} " +
                    "视频大小=${if (localSize > 0) "${localSize}B" else "未知(远端)"} " +
                    "localExists=$localExists cached=$cached " +
                    "src=${if (realUrl == playUrl) "direct" else "proxy"} url=$realUrl"
            )
        } else {
            android.util.Log.e(
                "VideoFlow",
                "[play] no url msgId=${message?.msgId} local=$local remote=$remote"
            )
        }
        controller = StandardVideoController(requireContext())

        controller?.apply {
            val completeView = CompleteView(context)
            val errorView = ErrorView(context)
            val prepareView = PrepareView(context)
            prepareView.setClickStart()
            addControlComponent(completeView, errorView, prepareView)
            val controlView = CustomControlView(context)
            controlView.fitsSystemWindows = true
            controlView.setGalleryVisible(showGallery)
            controlView.setOptionsVisible(showOptions)
            controlView.setOnControlEventListener(object :
                CustomControlView.OnControlEventListener {
                override fun showDownload(): Boolean {
                    return true
                }

                override fun openGallery() {
                    message?.also {
                        ARouter.getInstance().build(MainModule.FILE_MANAGEMENT)
                            .withString("target", it.contact)
                            .withInt("channelType", it.channelType)
                            .withInt("index", 1)
                            .navigation()
                        activity?.finish()
                    }
                }

                override fun saveVideo(downloadView: View) {
                    saveMedia(downloadView)
                }

                override fun forwardVideo() {
                    forward()
                }

                override fun close() {
                    controller?.gone()
                    activity?.finishAfterTransition()
                }
            })
            addControlComponent(controlView)
            addControlComponent(GestureView(context))
        }

        player.setVideoController(controller)
        if (startPlayWhenInit) {
            start()
        }
    }

    private fun fileExist(): Boolean {
        return if (message != null && forwardPos == -1) {
            message!!.localExists()
        } else if (forward != null) {
            forward!!.localExists()
        } else false
    }

    private fun saveMedia(downloadView: View?) {
        PermissionX.init(this)
            .permissions(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            .onExplain(this)
            .request { allGranted, _, _ ->
                if (allGranted) {
                    lifecycleScope.launch {
                        val result = if (message != null && forwardPos == -1) {
                            DownloadManager2.saveToDownload(message!!)
                        } else if (forward != null) {
                            DownloadManager2.saveToDownload(message!!, forwardPos)
                        } else if (url != null) {
                            DownloadManager2.saveToDownload(url!!)
                        } else {
                            return@launch
                        }
                        result.observe(viewLifecycleOwner) {
                            if (it is Result.Success) {
                                if (message != null && forwardPos == -1) {
                                    binding.videoPlayer.setUrl(message!!.msg.localUrl)
                                } else {
                                    binding.videoPlayer.setUrl(forward?.msg?.localUrl ?: url)
                                }
                                downloadView?.also { view ->
                                    toast(getString(R.string.chat_tips_video_download_to, it.data().absolutePath), Toast.LENGTH_LONG)
                                    view.gone()
                                }
                            } else if (it is Result.Error) {
                                toast(getString(R.string.chat_tips_download_fail, it.error().message))
                            }
                        }
                    }
                } else {
                    toast(R.string.permission_not_granted)
                }
            }
    }

    private fun forward() {
        when {
            message != null -> {
                lifecycleScope.launch {
                    val target = if (message!!.channelType == ChatConst.PRIVATE_CHANNEL) {
                        contactManager.getUserInfo(message?.contact ?: "", true)
                    } else {
                        contactManager.getGroupInfo(message?.contact ?: "")
                    }
                    val source = MessageSource.create(
                        message!!.channelType,
                        delegate.getAddress() ?: "",
                        delegate.toFriendUser().getRawName(),
                        target.getId(),
                        target.getRawName()
                    )
                    val msg = message!!.clone().also { it.source = source }
                    ForwardHelper.checkForwardFile(this@VideoGalleryFragment, arrayListOf(msg)) { error, list ->
                        if (error != null) {
                            toast(error)
                            return@checkForwardFile
                        }
                        ARouter.getInstance().build(MainModule.CONTACT_SELECT)
                            .withSerializable("messages", list)
                            .navigation()
                    }
                }
            }
            forward != null -> {
                lifecycleScope.launch {
                    val msg = ChatMessage.create("", "", 0, forward!!.msgType, forward!!.msg)
                    ForwardHelper.checkForwardFile(this@VideoGalleryFragment, arrayListOf(msg)) { error, list ->
                        if (error != null) {
                            toast(error)
                            return@checkForwardFile
                        }
                        ARouter.getInstance().build(MainModule.CONTACT_SELECT)
                            .withSerializable("messages", list)
                            .navigation()
                    }
                }
            }
            else -> {
                lifecycleScope.launch {
                    if (DownloadManager2.contains(url ?: "")) {
                        toast(R.string.chat_tips_is_downloading)
                        return@launch
                    }
                    loading(true)
                    DownloadManager2.downloadToApp(url ?: "").observe(viewLifecycleOwner) {
                        if (it.isSucceed()) {
                            val size = Utils.getImageSize(it.data().absolutePath ?: "")
                            val message = ChatMessage.create(
                                "",
                                "",
                                0,
                                Biz.MsgType.Image,
                                MessageContent.image(
                                    url,
                                    it.data().absolutePath,
                                    intArrayOf(size[0], size[1])
                                )
                            )
                            ARouter.getInstance().build(MainModule.CONTACT_SELECT)
                                .withSerializable("messages", arrayListOf(message))
                                .navigation()
                            dismiss()
                        } else if (it is Result.Error) {
                            dismiss()
                        }
                    }
                }
            }
        }
    }
}