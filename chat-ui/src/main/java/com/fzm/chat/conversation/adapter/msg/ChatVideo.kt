package com.fzm.chat.conversation.adapter.msg

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.R
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.model.isSendType
import com.fzm.chat.core.data.model.localExists
import com.fzm.chat.core.data.po.getDisplayUrl
import com.fzm.chat.core.media.DownloadManager2
import com.fzm.chat.core.utils.Utils
import com.fzm.chat.databinding.LayoutMsgVideoBinding
import com.qmuiteam.qmui.widget.QMUIProgressBar
import com.zjy.architecture.ext.dp
import com.zjy.architecture.ext.gone
import com.zjy.architecture.ext.visible
import dtalk.biz.Biz

/**
 * @author zhengjy
 * @since 2020/03/15
 * Description:
 */
class ChatVideo(listener: ChatMessageClickListener) : ChatBaseItem(listener) {

    companion object {

        val MAX: Int = 180.dp

        val MIN: Int = 80.dp

        /** 远端视频总大小缓存（HEAD Content-Length） */
        private val sizeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    private val options by lazy {
        RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .placeholder(R.drawable.biz_image_placeholder)
            .error(R.drawable.biz_image_placeholder)
    }

    override val itemViewType: Int
        get() = Biz.MsgType.Video_VALUE

    override val layoutId: Int
        get() = R.layout.item_msg_normal

    override fun setupView(holder: BaseViewHolder, item: ChatMessage) {
        val binding = getOrCreateBinding<LayoutMsgVideoBinding>(holder)
        binding.ivImage.tag = item.msgId
        val size = verifyThumbSize(item.msg.height, item.msg.width)
        binding.ivImage.layoutParams = binding.ivImage.layoutParams.apply {
            height = size[0]
            width = size[1]
        }
        Glide.with(context).load(item.msg.getDisplayUrl(context))
            .thumbnail(0.1f)
            .apply(options)
            .into(binding.ivImage)
        binding.tvDuration.text = Utils.formatVideoDuration(item.msg.duration)

        setProgress(
            binding.pbVideo,
            binding.ivState,
            binding.tvProgress,
            binding.tvProgressSize,
            binding.tvVideoLoadSize,
            item,
            item.progress
        )
        // 接收的视频消息通常没有 size 字段：HEAD 拿一次总大小，返回后刷新气泡右上角
        if (!item.isSendType) {
            val mediaUrl = item.msg.mediaUrl
            if (!mediaUrl.isNullOrEmpty() && item.msg.size <= 0 &&
                !sizeCache.containsKey(mediaUrl)
            ) {
                fetchRemoteSize(mediaUrl) {
                    holder.itemView.post {
                        if (binding.ivImage.tag == item.msgId) {
                            setProgress(
                                binding.pbVideo,
                                binding.ivState,
                                binding.tvProgress,
                                binding.tvProgressSize,
                                binding.tvVideoLoadSize,
                                item,
                                item.progress
                            )
                        }
                    }
                }
            }
        }
    }

    override fun setupView(holder: BaseViewHolder, item: ChatMessage, bundle: Bundle) {
        val binding = getOrCreateBinding<LayoutMsgVideoBinding>(holder)
        if (bundle.containsKey(ChatMessage.MSG_PROGRESS)) {
            setProgress(
                binding.pbVideo,
                binding.ivState,
                binding.tvProgress,
                binding.tvProgressSize,
                binding.tvVideoLoadSize,
                item,
                bundle.getFloat(ChatMessage.MSG_PROGRESS)
            )
        }
    }

    private fun setProgress(
        pbVideo: QMUIProgressBar,
        ivState: ImageView,
        tvProgress: TextView,
        tvProgressSize: TextView,
        tvLoadSize: TextView,
        item: ChatMessage,
        progress: Float
    ) {
        // 发送中（本地文件存在）显示上传进度；接收中（本地不存在）显示下载进度
        val show = progress > 0f && progress < 1f && (item.isSendType || !item.localExists())
        if (!show) {
            pbVideo.gone()
            tvProgress.gone()
            tvProgressSize.gone()
            tvLoadSize.gone()
            ivState.visible()
            return
        }
        pbVideo.visible()
        ivState.gone()
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        pbVideo.progress = percent
        tvProgress.text = "$percent%"
        tvProgress.visible()
        val total = totalSize(item)
        if (item.isSendType) {
            if (total > 0) {
                val uploaded = (total * progress).toLong().coerceIn(0, total)
                tvProgressSize.text = "${formatSize(uploaded)}/${formatSize(total)}"
                tvProgressSize.visible()
            } else {
                tvProgressSize.gone()
            }
            tvLoadSize.gone()
        } else {
            // 接收视频：右上角显示「已加载 KB / 总大小 M」
            tvProgressSize.gone()
            if (total > 0) {
                // 优先显示真实已下载字节（DownloadManager2 回调），没有再用进度估算
                val actual = DownloadManager2.downloadedBytes(item)
                val loaded = if (actual > 0) actual else (total * progress).toLong()
                tvLoadSize.text = "${formatSize(loaded.coerceIn(0, total))}/${formatSize(total)}"
                tvLoadSize.visible()
            } else {
                tvLoadSize.gone()
            }
        }
    }

    /** HEAD 请求获取远端视频大小，结果缓存，避免列表滚动重复请求 */
    private fun fetchRemoteSize(url: String, onResult: (Long) -> Unit) {
        Thread {
            val length = runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.instanceFollowRedirects = true
                conn.connect()
                val l = conn.contentLengthLong
                conn.disconnect()
                l
            }.getOrDefault(0L)
            if (length > 0) {
                sizeCache[url] = length
                onResult(length)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 视频总大小：优先消息里的 size，其次 HEAD 缓存，最后本地文件 / content uri */
    private fun totalSize(item: ChatMessage): Long {
        if (item.msg.size > 0) return item.msg.size
        DownloadManager2.downloadTotalBytes(item).takeIf { it > 0 }?.let { return it }
        val mediaUrl = item.msg.mediaUrl
        if (!mediaUrl.isNullOrEmpty()) {
            sizeCache[mediaUrl]?.takeIf { it > 0 }?.let { return it }
        }
        val path = item.msg.localUrl ?: return 0L
        if (path.startsWith("content://", ignoreCase = true)) {
            return runCatching {
                context.contentResolver.query(
                    android.net.Uri.parse(path),
                    arrayOf(android.provider.OpenableColumns.SIZE),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getLong(
                            cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)
                        )
                    } else 0L
                } ?: 0L
            }.getOrDefault(0L)
        }
        return runCatching { java.io.File(path).length() }.getOrDefault(0L)
    }

    /** 总大小统一用 M 显示，保留一位小数 */
    private fun formatSize(bytes: Long): String {
        return String.format(java.util.Locale.US, "%.1fM", bytes / 1024f / 1024f)
    }

    override fun chatLayout(holder: BaseViewHolder): View? {
        return getOrCreateBinding<LayoutMsgVideoBinding>(holder).ivImage
    }

    override fun onActionDown(view: View, item: ChatMessage) {

    }

    override fun onActionUp(view: View, item: ChatMessage) {

    }

    private fun verifyThumbSize(bitmapHeight: Int, bitmapWidth: Int): IntArray {
        val result = IntArray(2)
        if (bitmapHeight == 0 || bitmapWidth == 0) {
            result[0] = (MAX + MIN) / 2
            result[1] = (MAX + MIN) / 2
            return result
        }
        val isWidthImage = bitmapWidth - bitmapHeight >= 0
        if (isWidthImage) {
            //宽图
            when {
                bitmapHeight < MIN -> {
                    val ratio = bitmapWidth.toFloat() / bitmapHeight
                    result[0] = MIN
                    result[1] = (ratio * MIN).toInt().coerceAtMost(MAX)
                }
                bitmapWidth > MAX -> {
                    val ratio = bitmapHeight.toFloat() / bitmapWidth
                    result[0] = (ratio * MAX).toInt().coerceAtLeast(MIN)
                    result[1] = MAX
                }
                else -> {
                    result[0] = bitmapHeight
                    result[1] = bitmapWidth
                }
            }
        } else {
            //长图
            when {
                bitmapWidth < MIN -> {
                    val ratio = bitmapHeight.toFloat() / bitmapWidth
                    result[0] = (ratio * MIN).toInt().coerceAtMost(MAX)
                    result[1] = MIN
                }
                bitmapHeight > MAX -> {
                    val ratio = bitmapWidth.toFloat() / bitmapHeight
                    result[0] = MAX
                    result[1] = (ratio * MAX).toInt().coerceAtLeast(MIN)
                }
                else -> {
                    result[0] = bitmapHeight
                    result[1] = bitmapWidth
                }
            }
        }
        return result
    }
}