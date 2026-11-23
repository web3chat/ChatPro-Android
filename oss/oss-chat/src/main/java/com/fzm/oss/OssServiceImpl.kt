package com.fzm.oss

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.alibaba.android.arouter.facade.annotation.Route
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.router.core.CoreModule
import com.fzm.chat.router.core.CoreService
import com.fzm.chat.router.oss.MediaType
import com.fzm.chat.router.oss.OssModule
import com.fzm.chat.router.oss.OssService
import com.fzm.chat.router.route
import com.fzm.oss.sdk.ClientConfiguration
import com.fzm.oss.sdk.OSS
import com.fzm.oss.sdk.OSSClient
import com.fzm.oss.sdk.common.OSSCredentialProvider
import com.fzm.oss.sdk.common.OSSFederationToken
import com.fzm.oss.sdk.model.CancelMultipartUploadRequest
import com.fzm.oss.sdk.model.CompleteMultipartUploadRequest
import com.fzm.oss.sdk.model.InitMultipartUploadRequest
import com.fzm.oss.sdk.model.PutObjectRequest
import com.fzm.oss.sdk.net.progress.OSSProgressCallback
import com.fzm.oss.sdk.model.UploadPartRequest
import com.zjy.architecture.di.rootScope
import com.zjy.architecture.util.logE
import com.zjy.architecture.util.logI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * @author zhengjy
 * @since 2021/08/18
 * Description:
 */
@Route(path = OssModule.FZM_OSS)
class OssServiceImpl : OssService, CoroutineScope {

    private lateinit var mContext: Context
    private val delegate by rootScope.inject<LoginDelegate>()
    private val coreService by route<CoreService>(CoreModule.SERVICE)

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.Main

    private var folder = "chatList/picture/"

    private lateinit var mOss: OSS

    override fun init(context: Context) {
        mContext = context
        val conf = ClientConfiguration().apply {
            appId = OssConfig.APP_ID
        }
        mOss = OSSClient(mContext, ChatConfig.CENTRALIZED_SERVER, object : OSSCredentialProvider {
            override fun getFederationToken(): OSSFederationToken {
                return OSSFederationToken(coreService?.signAuth() ?: "")
            }
        }, conf)
    }

    override suspend fun uploadMedia(endPoint: String?, uri: Uri?, @MediaType type: Int, encrypt: Boolean): String {
        return withContext(coroutineContext) {
            if (uri == null) {
                logE("OSS 上传失败：uri 为空")
                return@withContext ""
            }
            folder = when (type) {
                MediaType.PICTURE -> "chatList/picture/"
                MediaType.AUDIO -> "chatList/audio/"
                MediaType.FILE -> "chatList/file/"
                MediaType.VIDEO -> "chatList/video/"
                else -> "chatList/picture/"
            }
            val key = getObjectKey(getExtension(mContext, uri), encrypt)
            val server = serverOf(endPoint)
            logI("OSS 上传开始（uri）：server=$server key=$key type=$type encrypt=$encrypt")
            try {
                val size = try {
                    mContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                } catch (e: Exception) {
                    0L
                }
                val url = if (size >= MULTIPART_THRESHOLD) {
                    // 相册视频通常是 content://，先拷到缓存再分片并发上传
                    val temp = File(mContext.cacheDir, "oss_upload_${System.currentTimeMillis()}.tmp")
                    try {
                        withContext(Dispatchers.IO) {
                            mContext.contentResolver.openInputStream(uri)?.use { input ->
                                temp.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        try {
                            multipartUpload(temp, key, endPoint)
                        } catch (e: Exception) {
                            // 服务端不支持分片时回退单次上传
                            logE("OSS 分片上传失败，回退单次上传（uri）：${e.javaClass.name}: ${e.message}")
                            mOss.putObject(
                                PutObjectRequest(key, file = temp, endPoint = endPoint)
                            ).url
                        }
                    } finally {
                        temp.delete()
                    }
                } else {
                    mOss.putObject(
                        PutObjectRequest(
                            key,
                            uploadUri = uri,
                            endPoint = endPoint
                        )
                    ).url
                }
                logI("OSS 上传成功（uri）：server=$server key=$key url=$url")
                url
            } catch (e: Exception) {
                logE("OSS 上传异常（uri）：server=$server key=$key ${e.javaClass.name}: ${e.message}")
                throw e
            }
        }
    }

    override suspend fun uploadMedia(endPoint: String?, path: String?, @MediaType type: Int, encrypt: Boolean): String {
        return withContext(coroutineContext) {
            if (path.isNullOrEmpty()) {
                logE("OSS 上传失败：path 为空")
                return@withContext ""
            }
            folder = when (type) {
                MediaType.PICTURE -> "chatList/picture/"
                MediaType.AUDIO -> "chatList/audio/"
                MediaType.FILE -> "chatList/file/"
                MediaType.VIDEO -> "chatList/video/"
                else -> "chatList/picture/"
            }
            val key = getObjectKey(getExtension(path), encrypt)
            val file = File(path)
            val server = serverOf(endPoint)
            logI("OSS 上传开始：server=$server key=$key size=${if (file.exists()) file.length() else -1L} type=$type encrypt=$encrypt")
            try {
                val url = if (file.length() >= MULTIPART_THRESHOLD) {
                    // 大文件（视频等）走分片并发上传，显著加快发送速度
                    try {
                        multipartUpload(file, key, endPoint)
                    } catch (e: Exception) {
                        // 服务端不支持分片时回退单次上传
                        logE("OSS 分片上传失败，回退单次上传：${e.javaClass.name}: ${e.message}")
                        mOss.putObject(
                            PutObjectRequest(key, file = file, endPoint = endPoint)
                        ).url
                    }
                } else {
                    mOss.putObject(
                        PutObjectRequest(
                            key,
                            file = file,
                            endPoint = endPoint
                        )
                    ).url
                }
                logI("OSS 上传成功：server=$server key=$key url=$url")
                url
            } catch (e: Exception) {
                logE("OSS 上传异常：server=$server key=$key ${e.javaClass.name}: ${e.message}")
                throw e
            }
        }
    }

    override suspend fun uploadMediaWithProgress(
        endPoint: String?,
        path: String?,
        @MediaType type: Int,
        encrypt: Boolean,
        onProgress: ((Float) -> Unit)?
    ): String {
        if (path.isNullOrEmpty()) {
            logE("OSS 上传失败：path 为空")
            return ""
        }
        // 相册视频会以 content:// 传入，单独走 uri 分支（同样带进度）
        val isContent = path.startsWith("content://", ignoreCase = true)
        return withContext(coroutineContext) {
            folder = when (type) {
                MediaType.PICTURE -> "chatList/picture/"
                MediaType.AUDIO -> "chatList/audio/"
                MediaType.FILE -> "chatList/file/"
                MediaType.VIDEO -> "chatList/video/"
                else -> "chatList/picture/"
            }
            val key = getObjectKey(
                if (isContent) getExtension(mContext, Uri.parse(path)) else getExtension(path),
                encrypt
            )
            val server = serverOf(endPoint)
            try {
                val url = if (isContent) {
                    val uri = Uri.parse(path)
                    val size = try {
                        mContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                    logI("OSS 上传开始（content 进度）：server=$server key=$key size=$size type=$type")
                    if (size >= MULTIPART_THRESHOLD) {
                        val temp = File(mContext.cacheDir, "oss_upload_${System.currentTimeMillis()}.tmp")
                        try {
                            withContext(Dispatchers.IO) {
                                mContext.contentResolver.openInputStream(uri)?.use { input ->
                                    temp.outputStream().use { output -> input.copyTo(output) }
                                }
                            }
                            try {
                                multipartUpload(temp, key, endPoint, onProgress)
                            } catch (e: Exception) {
                                logE("OSS 分片上传失败，回退单次上传（content）：${e.message}")
                                mOss.putObject(
                                    PutObjectRequest(key, file = temp, endPoint = endPoint),
                                    progressCallback(onProgress)
                                ).url
                            }
                        } finally {
                            temp.delete()
                        }
                    } else {
                        mOss.putObject(
                            PutObjectRequest(key, uploadUri = uri, endPoint = endPoint),
                            progressCallback(onProgress)
                        ).url
                    }
                } else {
                    val file = File(path)
                    val size = if (file.exists()) file.length() else -1L
                    logI("OSS 上传开始（进度）：server=$server key=$key size=$size type=$type")
                    if (size >= MULTIPART_THRESHOLD) {
                        try {
                            multipartUpload(file, key, endPoint, onProgress)
                        } catch (e: Exception) {
                            logE("OSS 分片上传失败，回退单次上传（进度）：${e.javaClass.name}: ${e.message}")
                            mOss.putObject(
                                PutObjectRequest(key, file = file, endPoint = endPoint),
                                progressCallback(onProgress)
                            ).url
                        }
                    } else {
                        mOss.putObject(
                            PutObjectRequest(key, file = file, endPoint = endPoint),
                            progressCallback(onProgress)
                        ).url
                    }
                }
                logI("OSS 上传成功（进度）：server=$server key=$key url=$url")
                url
            } catch (e: Exception) {
                logE("OSS 上传异常（进度）：server=$server key=$key ${e.javaClass.name}: ${e.message}")
                throw e
            }
        }
    }

    /** 单次上传的进度回调，按整数百分比节流 */
    private fun progressCallback(onProgress: ((Float) -> Unit)?): OSSProgressCallback<PutObjectRequest>? {
        if (onProgress == null) return null
        return object : OSSProgressCallback<PutObjectRequest> {
            private var lastPercent = -1

            override fun onProgress(request: PutObjectRequest, currentSize: Long, totalSize: Long) {
                if (totalSize <= 0L) return
                val percent = (currentSize * 100 / totalSize).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent / 100f)
                }
            }
        }
    }

    /** 实际请求的 OSS 服务器地址（endPoint 为空时 SDK 会回退到 CENTRALIZED_SERVER） */
    private fun serverOf(endPoint: String?): String =
        if (endPoint.isNullOrEmpty()) ChatConfig.CENTRALIZED_SERVER else endPoint

    @SuppressLint("SimpleDateFormat")
    private fun getObjectKey(suffix: String, encrypt: Boolean): String {
        val timeL = System.currentTimeMillis()
        val format = SimpleDateFormat("yyyyMMdd")
        val time = format.format(timeL)
        val format1 = SimpleDateFormat("HHmm")
        val time1 = format1.format(timeL)
        val format2 = SimpleDateFormat("ssSSS")
        val time2 = format2.format(timeL)
        val objectKey = StringBuilder("")
        try {
            objectKey.append(folder)
            objectKey.append(time)
            objectKey.append("/")
            if (ChatConfig.FILE_ENCRYPT && encrypt) {
                objectKey.append(ChatConst.ENC_PREFIX)
                objectKey.append("_")
            }
            objectKey.append(time)
            objectKey.append(time1)
            objectKey.append(time2)
            objectKey.append("_")
            objectKey.append(delegate.getAddress())
            objectKey.append("_")
            objectKey.append(generateRandomString(5))
            if (suffix.isNotEmpty()) {
                objectKey.append(".")
                objectKey.append(suffix)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return objectKey.toString()
    }

    private fun getExtension(context: Context, uri: Uri?): String {
        if (uri == null) return ""
        val ext = context.contentResolver.getType(uri)
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(ext) ?: ""
    }

    private fun getExtension(path: String): String = path.split(".").lastOrNull() ?: ""

    /**
     * 分片并发上传大文件（视频/大文件）
     *
     * 单次 putObject 上传 100MB 视频需要整包走一遍网络，容易超时且慢；
     * 这里拆成 4MB 分片，最多 4 个分片并发上传，最后合并。
     */
    private suspend fun multipartUpload(file: File, key: String, endPoint: String?, onProgress: ((Float) -> Unit)? = null): String {
        val size = file.length()
        if (size <= 0L) return ""
        val partSize = PART_SIZE
        val partCount = ((size + partSize - 1) / partSize).toInt().coerceAtMost(10000)
        val init = mOss.initMultipartUpload(InitMultipartUploadRequest(key, endPoint))
        val uploadId = init.uploadId
        logI("OSS 分片上传开始：key=$key size=$size parts=$partCount")
        return try {
            val semaphore = Semaphore(MAX_CONCURRENT_PARTS)
            val finished = AtomicInteger(0)
            val parts = coroutineScope {
                (0 until partCount).map { index ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val start = index.toLong() * partSize
                            val length = minOf(partSize, size - start).toInt()
                            val data = ByteArray(length)
                            RandomAccessFile(file, "r").use { raf ->
                                raf.seek(start)
                                raf.readFully(data)
                            }
                            val part = mOss.uploadPart(
                                UploadPartRequest(uploadId, index + 1, key, data, endPoint), null
                            )
                            onProgress?.invoke(finished.incrementAndGet().toFloat() / partCount)
                            CompleteMultipartUploadRequest.Part(part.ETag, part.partNumber)
                        }
                    }
                }.awaitAll()
            }
            val result = mOss.completeMultipartUpload(
                CompleteMultipartUploadRequest(uploadId, key, parts, endPoint)
            )
            logI("OSS 分片上传成功：key=$key url=${result.url}")
            result.url
        } catch (e: Exception) {
            logE("OSS 分片上传失败：key=$key ${e.javaClass.name}: ${e.message}")
            runCatching { mOss.cancelMultipartUpload(CancelMultipartUploadRequest(uploadId, key, endPoint)) }
            throw e
        }
    }

    companion object {

        /** 超过该大小走分片并发上传 */
        private const val MULTIPART_THRESHOLD = 8L * 1024 * 1024

        /** 单个分片大小 */
        private const val PART_SIZE = 4L * 1024 * 1024

        /** 并发上传分片数 */
        private const val MAX_CONCURRENT_PARTS = 4
    }

    private fun generateRandomString(length: Int): String {
        val str = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = Random()
        val sb = StringBuilder()
        for (i in 0 until length) {
            val number = random.nextInt(62)
            sb.append(str[number])
        }
        return sb.toString()
    }
}