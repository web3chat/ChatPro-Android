package com.fzm.chat.vm

import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.bean.Field
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.router.oss.MediaType
import com.fzm.chat.router.oss.OssModule
import com.fzm.chat.router.oss.OssService
import com.fzm.chat.router.route
import com.zjy.architecture.data.Result
import com.zjy.architecture.exception.ApiException
import com.zjy.architecture.mvvm.LoadingViewModel
import com.zjy.architecture.mvvm.request
import com.zjy.architecture.util.isContent
import com.zjy.architecture.util.logE
import com.zjy.architecture.util.logI
import java.io.File

/**
 * @author zhengjy
 * @since 2021/03/19
 * Description:
 */
class AccountViewModel(
    private val delegate: LoginDelegate
) : LoadingViewModel(), LoginDelegate by delegate {

    private val ossService by route<OssService>(OssModule.APP_OSS)

    private val _avatarResult by lazy { MutableLiveData<String>() }
    val avatarResult: LiveData<String>
        get() = _avatarResult

    fun setAvatar(path: String) {
        request<String> {
            onRequest {
                val url = uploadMedia(path, MediaType.PICTURE)
                if (url.isEmpty()) {
                    logE("头像上传失败：uploadMedia 返回空 url（path=$path）")
                    Result.Error(ApiException("upload fail"))
                } else {
                    logI("头像上传成功，开始更新用户资料：url=$url")
                    setUserInfo(listOf(Field(ChatConst.UserField.AVATAR, url, 1)))
                }
            }
            onSuccess {
                _avatarResult.value = path
            }
        }
    }

    /**
     * 上传头像图片
     *
     * ⚠️ 这里的异常以前被静默吞掉（只返回空串 → 界面提示 upload fail），排查不到原因，
     * 现在把「上传服务器地址 / 文件路径 / 文件大小 / 异常类型与信息」全部打日志。
     */
    private suspend fun uploadMedia(path: String?, @MediaType type: Int): String {
        val endPoint = delegate.servers.value?.firstOrNull()?.address
        if (path.isNullOrEmpty()) {
            logE("头像上传失败：path 为空（endPoint=${endPoint ?: "(空)"}）")
            return ""
        }
        val service = ossService
        if (service == null) {
            logE("头像上传失败：OssService 未注册（ossService == null，endPoint=${endPoint ?: "(空)"}）")
            return ""
        }
        val isContent = path.isContent()
        val fileSize = if (isContent) -1L else File(path).let { if (it.exists()) it.length() else -1L }
        logI("头像上传开始：endPoint=${endPoint ?: "(空=走 CENTRALIZED_SERVER)"} path=$path size=$fileSize isContent=$isContent")
        return try {
            val url = if (isContent) {
                service.uploadMedia(endPoint, path.toUri(), type)
            } else {
                service.uploadMedia(endPoint, path, type)
            }
            if (url.isEmpty()) {
                logE("头像上传失败：OSS 返回空 url（endPoint=${endPoint ?: "(空)"} path=$path）")
            } else {
                logI("头像上传完成：url=$url")
            }
            url
        } catch (e: Exception) {
            logE("头像上传异常：endPoint=${endPoint ?: "(空)"} path=$path ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            ""
        }
    }
}