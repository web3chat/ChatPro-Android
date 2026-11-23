package com.fzm.chat.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * 视频压缩（发送前）。
 *
 * 策略：优先兼容性 + 速度，全部走 mobile-ffmpeg 软件编解码（华为/小米/vivo 等均可用）：
 * - 小于 8MB 且高度 ≤720p 的视频不再压缩，直接原样发送
 * - 其余压到「宽 ≤960、高 ≤540、24fps」，H.264 + AAC，ultrafast / CRF30
 * - 压缩结果比原文件还大时回退原视频
 * - 失败（ffmpeg 报错/超时/输出为空）返回 null，由调用方回退原视频
 *
 * 相册选择的视频通常是 `content://` URI，ffmpeg 不能直接读，需要先拷到应用私有目录。
 */
object VideoCompressor {

    private const val TAG = "VideoCompress"

    /** 低于该大小且分辨率不高时不压缩 */
    private const val MIN_COMPRESS_SIZE = 8L * 1024 * 1024

    /** 压缩目标上限：500MB（通过码率上限保证不超） */
    private const val MAX_OUTPUT_SIZE = 500L * 1024 * 1024

    /** 压缩超时：最短 10 分钟，按视频时长放宽，最长 60 分钟 */
    private const val BASE_TIMEOUT_MS = 10 * 60 * 1000L
    private const val MAX_TIMEOUT_MS = 6 * 60 * 60 * 1000L

    data class Result(
        val path: String,
        val width: Int,
        val height: Int,
        val durationSec: Int,
        /** true=压缩后的文件，false=未压缩/原文件 */
        val compressed: Boolean
    )

    private data class Meta(val width: Int, val height: Int, val durationSec: Int)

    /**
     * @param onProgress 压缩进度（0~100），在 ffmpeg 回调线程触发，调用方自行切主线程
     */
    suspend fun compress(
        context: Context,
        inputPath: String,
        maxSizeBytes: Long = MAX_OUTPUT_SIZE,
        onProgress: ((Int) -> Unit)? = null
    ): Result? = withContext(Dispatchers.IO) {
        // content:// 先落到 App 私有 Video 目录（持久化，消息 localUrl 会引用它，不能放缓存后删除）
        val source = if (inputPath.startsWith("content://")) {
            copyToWorkDir(context, inputPath)
        } else {
            File(inputPath)
        } ?: run {
            Log.e(TAG, "视频源解析失败（content uri 拷贝失败）: $inputPath")
            return@withContext null
        }
        if (!source.exists() || source.length() <= 0) {
            Log.e(TAG, "视频源不存在或为空: ${source.absolutePath}")
            return@withContext null
        }
        val meta = readMeta(source.absolutePath)
        Log.d(
            TAG,
            "开始压缩：原视频大小=${source.length()}B 时长=${meta.durationSec}s"
        )
        // 小视频、已低于 720p：不值得压缩
        if (source.length() < MIN_COMPRESS_SIZE && meta.height in 1..720) {
            Log.d(TAG, "无需压缩：视频较小且 ≤720p，大小=${source.length()}B")
            onProgress?.invoke(100)
            return@withContext Result(
                source.absolutePath, meta.width, meta.height, meta.durationSec, false
            )
        }
        val output = File(videoDir(context), "video_compress_${System.currentTimeMillis()}.mp4")
        // 按「目标大小 / 时长」反推码率上限，保证压缩产物不超过 500MB；
        // 短视频目标码率过高时（>20Mbps）不加限制，交给 CRF 控制质量。
        val targetKbps = if (meta.durationSec > 0) {
            ((maxSizeBytes * 8L * 85 / 100) / meta.durationSec / 1000).toInt() - 64
        } else {
            0
        }
        val useBitrateCap = targetKbps in 50..20_000
        val command = buildString {
            // -flags2 +fast 让 H.264 解码走快速路径；sws 用双线性，缩小更快；均兼容各机型
            append("-y -flags2 +fast -sws_flags fast_bilinear -i \"")
            append(source.absolutePath).append("\" ")
            // 目标 540p、24fps；pad 到偶数宽高（libx264 要求偶数，竖屏 4K 旋转后可能是奇数宽）
            append("-vf \"scale='min(960,iw)':'min(540,ih)':force_original_aspect_ratio=decrease,")
            append("pad=ceil(iw/2)*2:ceil(ih/2)*2,fps=24\" ")
            append("-c:v libx264 -preset ultrafast -crf 30 -pix_fmt yuv420p -threads 0 ")
            if (useBitrateCap) {
                append("-maxrate ${targetKbps}k -bufsize ${targetKbps * 2}k ")
            }
            append("-c:a aac -b:a 64k ")
            append("-movflags +faststart ")
            append("\"").append(output.absolutePath).append("\"")
        }
        val durationMs = meta.durationSec * 1000L
        // 长视频软编耗时可能超过 10 分钟，按视频时长动态放宽
        val timeoutMs = ((meta.durationSec * 2L + 300L) * 1000L)
            .coerceIn(BASE_TIMEOUT_MS, MAX_TIMEOUT_MS)
        var lastPercent = -1

        fun report(timeMs: Int) {
            if (timeMs <= 0 || durationMs <= 0) return
            val percent = ((timeMs * 100L) / durationMs).toInt().coerceIn(0, 99)
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress?.invoke(percent)
            }
        }

        val start = System.currentTimeMillis()
        var sessionId = -1L
        val returnCode = try {
            // 清掉上一次压缩残留的统计，否则弹窗会先闪一下 99% 再回到 1%
            runCatching { Config.resetStatistics() }
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Int> { cont ->
                    sessionId = FFmpeg.executeAsync(command) { _, rc ->
                        if (cont.isActive) cont.resume(rc)
                    }
                    // 只处理当前 session 的统计；enable 放在 executeAsync 之后，避免收到旧 session 的回调
                    Config.enableStatisticsCallback { stats ->
                        if (stats.executionId == sessionId) {
                            report(stats.time)
                        }
                    }
                    cont.invokeOnCancellation {
                        runCatching { if (sessionId > 0) FFmpeg.cancel(sessionId) }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户点取消：终止 ffmpeg 并把取消抛给上层，不能继续走「发原视频」
            runCatching { if (sessionId > 0) FFmpeg.cancel(sessionId) }
            output.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ffmpeg 执行异常: ${e.javaClass.simpleName}: ${e.message}", e)
            runCatching { if (sessionId > 0) FFmpeg.cancel(sessionId) }
            output.delete()
            return@withContext null
        } finally {
            Config.enableStatisticsCallback { }
        }
        val cost = System.currentTimeMillis() - start
        if (returnCode == null) {
            Log.e(TAG, "压缩超时 timeout=${timeoutMs}ms cost=${cost}ms")
            runCatching { if (sessionId > 0) FFmpeg.cancel(sessionId) }
            output.delete()
            return@withContext null
        }
        val success = returnCode == Config.RETURN_CODE_SUCCESS
        if (!success || !output.exists() || output.length() <= 0) {
            Log.e(TAG, "压缩失败 returnCode=$returnCode cost=${cost}ms")
            output.delete()
            return@withContext null
        }
        // 压完反而更大：用原视频
        if (output.length() >= source.length()) {
            Log.w(TAG, "压缩后更大(${output.length()} >= ${source.length()})，回退原视频")
            output.delete()
            onProgress?.invoke(100)
            return@withContext Result(
                source.absolutePath, meta.width, meta.height, meta.durationSec, false
            )
        }
        val outMeta = readMeta(output.absolutePath)
        onProgress?.invoke(100)
        Log.d(
            TAG,
            "结束压缩：耗时=${cost}ms 压缩后大小=${output.length()}B"
        )
        Result(
            path = output.absolutePath,
            width = outMeta.width,
            height = outMeta.height,
            durationSec = outMeta.durationSec,
            compressed = true
        )
    }

    /** content:// → App 私有 Video 目录文件 */
    private fun copyToWorkDir(context: Context, path: String): File? {
        return try {
            val file = File(videoDir(context), "video_src_${System.currentTimeMillis()}.mp4")
            context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (file.exists() && file.length() > 0) file else {
                file.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "content uri 拷贝失败: ${e.message}", e)
            null
        }
    }

    /** App 私有视频目录（外部 files/Video，卸载/清缓存不会丢） */
    private fun videoDir(context: Context): File {
        val dir = context.getExternalFilesDir("Video") ?: File(context.filesDir, "Video")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun readMeta(path: String): Meta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Meta(width, height, (durationMs / 1000).toInt())
        } catch (e: Exception) {
            Meta(0, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }
}
