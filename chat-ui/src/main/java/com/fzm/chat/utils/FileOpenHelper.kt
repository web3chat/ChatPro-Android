package com.fzm.chat.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizFileProvider
import com.zjy.architecture.ext.toast
import com.zjy.architecture.util.InstallUtil
import com.zjy.architecture.util.isContent
import java.io.File
import java.util.Locale

/**
 * 统一的「用其他应用打开文件」入口。
 *
 * 原实现直接拿 `ContentResolver.getType(uri)` 的结果去 setDataAndType，
 * 一旦拿不到 MIME（文件名无扩展名、扩展名大小写异常、部分 ROM 裁剪了 MimeTypeMap），
 * 或者设备上没有能处理该类型的应用，用户看到的就是「点了没反应 / 不支持的文件类型」。
 * Word、PPT 这类必须依赖三方 Office 应用的文件最容易踩到。
 *
 * 这里做了三件事：
 * 1. MIME 多级兜底：ContentResolver -> 常见 Office 扩展名映射 -> MimeTypeMap -> 通配类型；
 * 2. 显式授予读权限（FLAG_GRANT_READ_URI_PERMISSION + ClipData），确保 WPS / Office 等应用能拿到文件；
 * 3. 找不到可处理应用、文件丢失、URI 权限异常等都有明确提示，不再静默失败。
 */
object FileOpenHelper {

    private const val TAG = "FileOpen"

    /** 常见文档类型的 MIME 兜底表（部分设备/文件名取不到 MIME 时使用） */
    private val MIME_BY_EXTENSION = mapOf(
        "doc" to "application/msword",
        "dot" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "csv" to "text/comma-separated-values",
        "zip" to "application/zip",
        "rar" to "application/x-rar-compressed",
        "7z" to "application/x-7z-compressed",
        "apk" to "application/vnd.android.package-archive"
    )

    /**
     * 把本地路径（或 content:// 地址）转成可分享给三方应用的 Uri
     */
    fun uriFor(context: Context, path: String): Uri {
        return if (path.isContent()) {
            Uri.parse(path)
        } else {
            FileProvider.getUriForFile(context, BizFileProvider.authority(context), File(path))
        }
    }

    /**
     * 用三方应用打开文件
     *
     * @param path     本地文件路径或 content:// 地址
     * @param fileName 原始文件名（用于扩展名兜底，可空）
     * @return true 表示已经成功拉起打开动作
     */
    fun open(context: Context, path: String?, fileName: String? = null): Boolean {
        if (path.isNullOrEmpty()) {
            Log.e(TAG, "打开失败：路径为空")
            context.toast(R.string.chat_tips_file_not_exist)
            return false
        }
        if (!path.isContent() && !File(path).exists()) {
            // 文件没真正落盘（下载/解密失败、被清理）时给出明确提示，避免"点了没反应"
            Log.e(TAG, "打开失败：文件不存在，path=$path")
            context.toast(R.string.chat_tips_file_not_exist)
            return false
        }
        return try {
            val uri = uriFor(context, path)
            val mime = resolveMime(context, uri, fileName ?: path)
            Log.d(TAG, "open path=$path, name=$fileName, mime=$mime, uri=$uri")
            if (mime == "application/vnd.android.package-archive") {
                InstallUtil.install(context, uri)
                return true
            }
            val target = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("file", uri)
            }
            // 有默认应用时直接打开；多个候选时系统会自己弹选择器
            val resolved = target.resolveActivity(context.packageManager)
            val handlers = context.packageManager.queryIntentActivities(target, 0)
            Log.d(TAG, "resolved=$resolved, handlers=${handlers.map { it.activityInfo.packageName }}")
            if (resolved == null && handlers.isEmpty()) {
                context.toast(R.string.chat_tips_unsupported_file_type)
                return false
            }
            if (context !is Activity) {
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(target)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "找不到可打开该文件的应用：${e.message}")
            context.toast(R.string.chat_tips_unsupported_file_type)
            false
        } catch (e: Exception) {
            // IllegalArgumentException（FileProvider 路径未配置）/ SecurityException（URI 权限）等
            Log.e(TAG, "打开文件异常：${Log.getStackTraceString(e)}")
            context.toast(R.string.chat_tips_unsupported_file_type)
            false
        }
    }

    /**
     * MIME 多级兜底解析：ContentResolver -> 扩展名映射表 -> MimeTypeMap -> 通配
     */
    private fun resolveMime(context: Context, uri: Uri, nameOrPath: String): String {
        val fromResolver = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (!fromResolver.isNullOrEmpty() && fromResolver != "application/octet-stream") {
            return fromResolver
        }
        val extension = nameOrPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
        MIME_BY_EXTENSION[extension]?.let { return it }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: fromResolver
            ?: "*/*"
    }
}
