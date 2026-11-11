package com.fzm.chat.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.model.contact
import com.fzm.chat.core.data.model.isSendType
import com.fzm.chat.core.logic.BurnConfirmScheduler
import com.fzm.chat.core.media.DownloadManager2
import com.fzm.chat.databinding.ActivityBurnDetailBinding
import com.fzm.chat.media.manager.MediaManager
import com.fzm.chat.utils.FileOpenHelper
import com.zjy.architecture.data.Result
import com.zjy.architecture.ext.toast
import dtalk.biz.Biz
import kotlinx.coroutines.launch
import java.io.File

/**
 * 阅后即焚独立详情页：打开即落库，关闭后发 burn-confirm，禁止左右滑到其它消息。
 */
class BurnDetailActivity : BizActivity() {

    companion object {
        private const val EXTRA_MESSAGE = "message"

        fun open(context: Context, message: ChatMessage) {
            context.startActivity(Intent(context, BurnDetailActivity::class.java).apply {
                putExtra(EXTRA_MESSAGE, message)
            })
        }
    }

    private val binding by init<ActivityBurnDetailBinding>()
    private var message: ChatMessage? = null
    private var confirmStarted = false

    override val root: View
        get() = binding.root

    override fun initView() {
        message = intent.getSerializableExtra(EXTRA_MESSAGE) as? ChatMessage
        binding.ctbTitle.setTitle(getString(R.string.chat_title_burn_detail))
        binding.ctbTitle.setRightVisible(false)
        binding.ctbTitle.setOnLeftClickListener { finish() }
    }

    override fun initData() {
        val msg = message
        if (msg == null) {
            finish()
            return
        }
        // 打开即已读：正文开始展示就写入待确认表（收方）
        if (!msg.isSendType) {
            android.util.Log.d("BurnFlow", "BurnDetail open: logId=${msg.logId} targetId=${msg.contact} from=${msg.from} isSend=${msg.isSendType}")
            lifecycleScope.launch {
                BurnConfirmScheduler.markOpened(msg.logId, msg.contact)
            }
        } else {
            android.util.Log.d("BurnFlow", "BurnDetail open (sender, no confirm): logId=${msg.logId}")
        }
        render(msg)
    }

    override fun setEvent() {
        binding.btnAction.setOnClickListener {
            val msg = message ?: return@setOnClickListener
            when (Biz.MsgType.forNumber(msg.msgType)) {
                Biz.MsgType.Audio -> playAudio(msg)
                Biz.MsgType.File -> openLocalFile(msg)
                else -> Unit
            }
        }
    }

    override fun onPause() {
        tryConfirm()
        super.onPause()
    }

    override fun onDestroy() {
        MediaManager.stop()
        if (binding.videoView.isVisible) {
            binding.videoView.stopPlayback()
        }
        tryConfirm()
        super.onDestroy()
    }

    /**
     * 关闭详情才 confirm；发方自己不调
     */
    private fun tryConfirm() {
        val msg = message ?: return
        if (msg.isSendType || confirmStarted) return
        confirmStarted = true
        android.util.Log.d("BurnFlow", "tryConfirm: logId=${msg.logId} targetId=${msg.contact}")
        BurnConfirmScheduler.confirmAsync(msg.logId, msg.contact)
    }

    private fun render(msg: ChatMessage) {
        when (Biz.MsgType.forNumber(msg.msgType)) {
            Biz.MsgType.Text -> {
                binding.svText.isVisible = true
                binding.tvText.text = msg.msg.content.orEmpty()
            }
            Biz.MsgType.Image -> {
                binding.ivImage.isVisible = true
                ensureDownloaded(msg) { showImage(msg) }
            }
            Biz.MsgType.Video -> {
                binding.videoView.isVisible = true
                ensureDownloaded(msg) { playVideo(msg) }
            }
            Biz.MsgType.Audio -> {
                binding.llFile.isVisible = true
                binding.btnAction.setText(R.string.chat_burn_play)
                binding.tvFileName.text = getString(R.string.chat_burn_audio)
            }
            Biz.MsgType.File -> {
                binding.llFile.isVisible = true
                binding.btnAction.setText(R.string.chat_burn_open)
                binding.tvFileName.text = msg.msg.fileName.orEmpty()
            }
            else -> {
                // 其他类型按文本兜底展示
                binding.svText.isVisible = true
                binding.tvText.text = msg.msg.content.orEmpty()
            }
        }
    }

    /**
     * 打开详情时才下载（不预下载），下载完成后回调本地路径
     */
    private fun ensureDownloaded(msg: ChatMessage, onReady: (String) -> Unit) {
        val local = msg.msg.localUrl
        if (!local.isNullOrEmpty() && File(local).exists()) {
            onReady(local)
            return
        }
        DownloadManager2.downloadToApp(msg).observe(this) {
            when (it) {
                is Result.Success -> {
                    msg.msg.localUrl = it.data.absolutePath
                    onReady(it.data.absolutePath.orEmpty())
                }
                is Result.Error -> toast(getString(R.string.chat_tips_download_fail, it.error().message))
                else -> Unit
            }
        }
    }

    private fun showImage(msg: ChatMessage) {
        Glide.with(this).load(msg.msg.localUrl).into(binding.ivImage)
    }

    private fun playVideo(msg: ChatMessage) {
        binding.videoView.setVideoPath(msg.msg.localUrl)
        binding.videoView.setOnPreparedListener { it.start() }
        binding.videoView.start()
    }

    private fun playAudio(msg: ChatMessage) {
        ensureDownloaded(msg) { path ->
            MediaManager.play(this, path, AudioManager.MODE_NORMAL, MediaPlayer.OnCompletionListener { })
        }
    }

    private fun openLocalFile(msg: ChatMessage) {
        ensureDownloaded(msg) { path ->
            FileOpenHelper.open(this, path, msg.msg.fileName)
        }
    }
}
