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
import com.fzm.oss.sdk.model.PutObjectRequest
import com.zjy.architecture.di.rootScope
import com.zjy.architecture.util.logE
import com.zjy.architecture.util.logI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
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
                val result = mOss.putObject(
                    PutObjectRequest(
                        key,
                        uploadUri = uri,
                        endPoint = endPoint
                    )
                )
                logI("OSS 上传成功（uri）：server=$server key=$key url=${result.url}")
                result.url
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
                val result = mOss.putObject(
                    PutObjectRequest(
                        key,
                        file = file,
                        endPoint = endPoint
                    )
                )
                logI("OSS 上传成功：server=$server key=$key url=${result.url}")
                result.url
            } catch (e: Exception) {
                logE("OSS 上传异常：server=$server key=$key ${e.javaClass.name}: ${e.message}")
                throw e
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