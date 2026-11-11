package com.fzm.chat.core.logic

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.fzm.arch.connection.Disposable
import com.fzm.arch.connection.utils.urlKey
import com.fzm.chat.core.crypto.toEncryptFile
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.data.ChatDatabase
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.model.*
import com.fzm.chat.core.data.msg.MsgState
import com.fzm.chat.core.data.msg.SendFailReason
import com.fzm.chat.core.data.msg.toSendFailReason
import com.fzm.chat.core.media.DownloadManager2
import com.fzm.chat.router.oss.MediaType
import com.fzm.chat.router.oss.OssModule
import com.fzm.chat.router.oss.OssService
import com.fzm.chat.router.route
import com.zjy.architecture.util.FileUtils
import com.zjy.architecture.util.isContent
import com.zjy.architecture.util.logD
import com.zjy.architecture.util.logE
import dtalk.biz.Biz
import kotlinx.coroutines.*
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.*
import kotlin.collections.ArrayList
import kotlin.coroutines.CoroutineContext

/**
 * @author zhengjy
 * @since 2021/12/06
 * Description:
 */
class MessageSender(
    private val context: Context,
    private val socketSender: SocketSender,
    private val httpSender: HttpSender,
): CoroutineScope, Disposable {

    companion object {

        private val pendingSend = Collections.synchronizedList(ArrayList<String>())

        fun isSending(message: ChatMessage): Boolean {
            val sending = SocketSender.isSending(message.msgId) || HttpSender.isSending(message.msgId)
            val pending = pendingSend.contains(message.msgId)
            return sending || pending
        }
    }

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.Main.immediate

    private val ossService by route<OssService>(OssModule.APP_OSS)

    private val database: ChatDatabase
        get() = ChatDatabaseProvider.provide()

    /**
     * 重新发送消息，手动触发消息重发
     *
     * @param url       发送的目标服务器
     * @param message   要发送的聊天消息
     */
    fun resend(url: String, message: ChatMessage) = launch {
        logD(
            "MsgFlow",
            "[resend] msgId=${message.msgId} logId=${message.logId} channel=${message.channelType} " +
                "contact=${message.contact} type=${message.msgType} lastReason=${message.failReason} " +
                "lastDetail=${message.failDetail} url=${url.urlKey()}"
        )
        message.failReason = null
        message.failDetail = null
        message.datetime = System.currentTimeMillis()
        message.state = MsgState.SENDING
        database.messageDao().insert(message.toMessagePO())
        database.recentSessionDao().updateRecentLog(message.contact, message.channelType)
        MessageSubscription.onUpdateContent(message)
        updateMessageState(message)
        send(url, message)
    }

    /**
     * 发送消息，如果是需要上传文件资源的消息类型，则自动上传文件，消息发送回调由
     * MessageSubscription.subscribeMessage分发
     *
     * @param url       发送的目标服务器
     * @param message   要发送的聊天消息
     */
    fun send(url: String, message: ChatMessage) {
        message.failReason = null
        message.failDetail = null
        pendingSend.add(message.msgId)
        logD(
            "MsgFlow",
            "[send] start msgId=${message.msgId} logId=${message.logId} channel=${message.channelType} " +
                "contact=${message.contact} from=${message.from} target=${message.target} " +
                "type=${message.msgType} fileType=${message.isFileType} url=${url.urlKey()}"
        )
        launch {
            try {
                sendSuspend(url, message)
            } catch (e: CancellationException) {
                logD("MsgFlow", "[send] cancelled msgId=${message.msgId}, state=${message.state}")
                throw e
            } catch (e: Exception) {
                logE("MsgFlow", "[send] exception msgId=${message.msgId}: ${Log.getStackTraceString(e)}")
                failMessage(message, e.toSendFailReason(), e.message ?: e.javaClass.simpleName)
            } finally {
                pendingSend.remove(message.msgId)
                logD(
                    "MsgFlow",
                    "[send] finish msgId=${message.msgId} state=${message.state} " +
                        "fail=${message.failReason} detail=${message.failDetail}"
                )
            }
        }
    }

    private suspend fun sendSuspend(url: String, message: ChatMessage) {
        logD(
            "MsgFlow",
            "[send] prepare msgId=${message.msgId} type=${message.msgType} " +
                "localUrl=${message.msg.localUrl} mediaUrl=${message.msg.mediaUrl} " +
                "fileType=${message.isFileType} encrypt=${ChatConfig.ENCRYPT} fileEncrypt=${ChatConfig.FILE_ENCRYPT}"
        )
        MessageSubscription.onReceiveMessage(message)
        insertLocal(message)
        if (message.isFileType && message.msg.localUrl.isContent()) {
            // 如果文件不在应用内部存储，则复制一份到内部存储，作为本地文件地址
            val bundle = FileUtils.queryFile(context, message.msg.localUrl)
            val displayName = bundle?.getString(MediaStore.MediaColumns.DISPLAY_NAME)
            val (folder, name) = DownloadManager2.getFileStorePath(
                message.msgType,
                message.logId,
                message.msg.fileName,
                displayName
            )
            val file = FileUtils.createFile(context, folder, name)
            withContext(Dispatchers.IO) {
                try {
                    val start = System.currentTimeMillis()
                    context.contentResolver.openInputStream(Uri.parse(message.msg.localUrl))
                        ?.use { input ->
                            BufferedOutputStream(FileOutputStream(file)).use {
                                input.copyTo(it)
                                it.flush()
                            }
                        }
                    message.msg.localUrl = file.absolutePath
                    MessageSubscription.onUpdateContent(message)
                    updateMessage(message)
                    logD(
                        "MsgFlow",
                        "[send] copy file ok msgId=${message.msgId} path=${file.absolutePath} " +
                            "cost=${System.currentTimeMillis() - start}ms"
                    )
                } catch (e: Exception) {
                    logE(
                        "MsgFlow",
                        "[send] copy file failed msgId=${message.msgId} uri=${message.msg.localUrl}: " +
                            Log.getStackTraceString(e)
                    )
                    e.printStackTrace()
                }
            }
        }

        var needUpload = false
        val uploadSuccess = when {
            message.msgType == Biz.MsgType.Forward_VALUE -> {
                needUpload = true
                uploadForwardFile(url, message.msg.forwardLogs, message.contact, message)
            }
            message.isFileType -> {
                needUpload = true
                uploadMessageFile(url, message)
            }
            else -> true
        }
        if (uploadSuccess) {
            if (needUpload) {
                MessageSubscription.onUpdateContent(message)
                updateMessage(message)
            }
            logD("MsgFlow", "[send] upload done msgId=${message.msgId} needUpload=$needUpload, send to server")
            // 将构建好的消息发送到服务端
            sendMessageInner(url, message)
        } else {
            logE(
                "MsgFlow",
                "[send] upload failed msgId=${message.msgId} reason=${message.failReason} " +
                    "detail=${message.failDetail}"
            )
            updateMessage(message)
            failMessage(message, message.failReason ?: SendFailReason.UPLOAD_FAILED, message.failDetail)
        }
    }

    /**
     * 统一处理发送失败：更新状态、落库并通知当前会话展示失败原因
     */
    private suspend fun failMessage(
        message: ChatMessage,
        reason: SendFailReason,
        detail: String? = null
    ) {
        if (message.failReason == null) {
            message.failReason = reason
        }
        if (message.failDetail.isNullOrEmpty()) {
            message.failDetail = detail
        }
        message.state = MsgState.FAIL
        logE(
            "MsgFlow",
            "[send] fail msgId=${message.msgId} logId=${message.logId} channel=${message.channelType} " +
                "contact=${message.contact} type=${message.msgType} reason=${message.failReason} " +
                "detail=${message.failDetail}"
        )
        updateMessageState(message)
        MessageSubscription.onUpdateState(message)
        MessageSubscription.onSendFail(message)
    }

    /**
     * 发送消息
     *
     * 如果已经连接了应对地址的webSocket，则直接通过连接发送；否则走http通道发送
     *
     * @param url       发送的目标服务器
     * @param message   要发送的聊天消息
     */
    private suspend fun sendMessageInner(url: String, message: ChatMessage) {
        // 语音/视频聊天邀请（RTCCall）：socket 通道下服务端不回 ack（实测一直「发送中」直到超时），
        // 改走 HTTP /record/push2 通道：既能避免无限 loading，也能拿到服务端明确的错误码/原因。
        if (message.msgType == Biz.MsgType.RTCCall_VALUE) {
            logD(
                "MsgFlow",
                "[send] rtc-invite via http msgId=${message.msgId} channel=${message.channelType} url=${url.urlKey()}"
            )
            httpSender.sendMessage(url, message)
            return
        }
        if (socketSender.sendMessage(url, message)) {
            logD("MsgFlow", "[send] via socket msgId=${message.msgId} url=${url.urlKey()}")
            return
        }
        logD("MsgFlow", "[send] socket unavailable, fallback to http msgId=${message.msgId} url=${url.urlKey()}")
        httpSender.sendMessage(url, message)
    }

    /**
     * 插入消息本地数据库
     *
     * @param message   聊天消息
     */
    private suspend fun insertLocal(message: ChatMessage) {
        database.recentSessionDao().insertMessage(message.toMessagePO(), 0, false)
    }

    /**
     * 更新消息本地数据库
     *
     * @param message   聊天消息
     */
    private suspend fun updateMessage(message: ChatMessage) {
        database.messageDao().updateMessageContent(message.msgId, message.msg)
    }

    /**
     * 更新本地消息状态
     *
     * @param message   聊天消息
     */
    private suspend fun updateMessageState(message: ChatMessage) {
        database.messageDao().updateMsgState(message.msgId, message.state)
    }

    /**
     * 上传要转发的消息中的文件
     *
     * @return 是否全部上传成功
     */
    private suspend fun uploadMessageFile(url: String, message: ChatMessage): Boolean {
        val type = when (message.msgType) {
            Biz.MsgType.Image_VALUE -> MediaType.PICTURE
            Biz.MsgType.Audio_VALUE -> MediaType.AUDIO
            Biz.MsgType.Video_VALUE -> MediaType.VIDEO
            Biz.MsgType.File_VALUE -> MediaType.FILE
            else -> -1
        }
        if (type != -1 && message.msg.mediaUrl.isNullOrEmpty()) {
            try {
                val start = System.currentTimeMillis()
                if (ChatConfig.FILE_ENCRYPT) {
                    val path = message.msg.localUrl?.toEncryptFile(context, message.contact)?.absolutePath
                    message.msg.mediaUrl = uploadMedia(url, path, type, message = message)
                } else {
                    message.msg.mediaUrl = uploadMedia(url, message.msg.localUrl, type, message = message)
                }
                logD(
                    "MsgFlow",
                    "[send] upload file msgId=${message.msgId} type=$type " +
                        "mediaUrl=${message.msg.mediaUrl} cost=${System.currentTimeMillis() - start}ms"
                )
            } catch (e: Exception) {
                logE(
                    "MsgFlow",
                    "[send] upload file exception msgId=${message.msgId} type=$type: " +
                        Log.getStackTraceString(e)
                )
                message.failReason = SendFailReason.UPLOAD_FAILED
                message.failDetail = e.message ?: e.javaClass.simpleName
                return false
            }
            if (message.msg.mediaUrl.isNullOrEmpty()) {
                if (message.failReason == null) {
                    message.failReason = SendFailReason.UPLOAD_FAILED
                }
                if (message.failDetail.isNullOrEmpty()) {
                    message.failDetail = "upload result empty (type=$type)"
                }
                return false
            }
        }
        return true
    }

    /**
     * 上传转发类型消息中，被转发的消息中的文件
     *
     * @return 是否全部上传成功
     */
    private suspend fun uploadForwardFile(
        url: String,
        list: List<ForwardMsg>?,
        target: String,
        message: ChatMessage
    ): Boolean {
        val start = System.currentTimeMillis()
        try {
            // 使用coroutineScope包裹上传任务，有一个任务失败，则取消整个上传任务
            return coroutineScope {
                val deferredQueue = ArrayDeque<Deferred<Unit>>()
                list?.forEach {
                    val type = when (it.msgType) {
                        Biz.MsgType.Image_VALUE -> MediaType.PICTURE
                        Biz.MsgType.Audio_VALUE -> MediaType.AUDIO
                        Biz.MsgType.Video_VALUE -> MediaType.VIDEO
                        Biz.MsgType.File_VALUE -> MediaType.FILE
                        else -> -1
                    }
                    if (type != -1 && it.msg.mediaUrl.isNullOrEmpty()) {
                        if (ChatConfig.FILE_ENCRYPT) {
                            deferredQueue.add(async {
                                val path = it.msg.localUrl?.toEncryptFile(context, target)?.absolutePath
                                it.msg.mediaUrl = uploadMedia(url, path, type, true, message)
                            })
                        } else {
                            deferredQueue.add(async {
                                it.msg.mediaUrl = uploadMedia(url, it.msg.localUrl, type, true, message)
                            })
                        }
                    }
                }
                while (deferredQueue.isNotEmpty()) {
                    deferredQueue.poll()?.await()
                }
                return@coroutineScope list?.none {
                    it.isFileType && it.msg.mediaUrl.isNullOrEmpty()
                } ?: true
            }
        } catch (e: Exception) {
            logE(
                "MsgFlow",
                "[send] forward upload failed msgId=${message.msgId}: ${Log.getStackTraceString(e)}"
            )
            message.failReason = SendFailReason.UPLOAD_FAILED
            message.failDetail = e.message ?: e.javaClass.simpleName
            return false
        } finally {
            logD(
                "MsgFlow",
                "[send] forward upload cost=${System.currentTimeMillis() - start}ms msgId=${message.msgId}"
            )
        }
    }

    /**
     * 上传文件资源
     */
    private suspend fun uploadMedia(
        url: String,
        path: String?,
        @MediaType type: Int,
        throws: Boolean = false,
        message: ChatMessage? = null
    ): String {
        return try {
            if (path.isContent()) {
                ossService?.uploadMedia(url, path?.toUri(), type) ?: ""
            } else {
                ossService?.uploadMedia(url, path, type) ?: ""
            }
        } catch (e: Exception) {
            logE("MsgFlow", "[send] uploadMedia exception type=$type path=$path: ${Log.getStackTraceString(e)}")
            message?.let {
                it.failReason = SendFailReason.UPLOAD_FAILED
                it.failDetail = e.message ?: e.javaClass.simpleName
            }
            // CrashReport.postCatchedException(Exception("文件上传失败", e))
            if (throws) throw e
            ""
        }
    }

    override fun dispose() {
        coroutineContext.cancel()
        socketSender.dispose()
    }
}