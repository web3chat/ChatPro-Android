package com.fzm.chat.router.oss

import android.net.Uri
import com.alibaba.android.arouter.facade.template.IProvider

/**
 * @author zhengjy
 * @since 2021/01/28
 * Description:
 */
interface OssService : IProvider {

    /**
     * 上传文件，返回url
     *
     * @param encrypt 是否加密上传（objectKey 带 $ENC$ 前缀）。广场等公开图片需传 false
     */
    suspend fun uploadMedia(endPoint: String? = null, uri: Uri?, @MediaType type: Int, encrypt: Boolean = true): String

    /**
     * 上传文件，返回url
     *
     * @param encrypt 是否加密上传（objectKey 带 $ENC$ 前缀）。广场等公开图片需传 false
     */
    suspend fun uploadMedia(endPoint: String? = null, path: String?, @MediaType type: Int, encrypt: Boolean = true): String
}