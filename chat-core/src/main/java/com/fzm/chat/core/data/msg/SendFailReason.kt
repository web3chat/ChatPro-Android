package com.fzm.chat.core.data.msg

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 消息发送失败原因（仅用于日志和 UI 展示，不写入数据库）
 *
 * @author zhengjy
 * @since 2026/10/19
 */
enum class SendFailReason {
    /** 设备没有可用网络 */
    NETWORK_UNAVAILABLE,

    /** 网络请求超时 */
    TIMEOUT,

    /** 无法连接服务器（DNS 失败、拒绝连接等） */
    CONNECT_FAILED,

    /** 图片/视频等文件上传失败 */
    UPLOAD_FAILED,

    /** 服务器返回错误 */
    SERVER_ERROR,

    /** 其他未知错误 */
    UNKNOWN
}

/**
 * 将发送过程中捕获的异常归类为对用户有意义的失败原因
 */
fun Throwable?.toSendFailReason(): SendFailReason = when (this) {
    is SocketTimeoutException -> SendFailReason.TIMEOUT
    is InterruptedIOException -> SendFailReason.TIMEOUT
    is UnknownHostException -> SendFailReason.CONNECT_FAILED
    is ConnectException -> SendFailReason.CONNECT_FAILED
    is NoRouteToHostException -> SendFailReason.CONNECT_FAILED
    is SSLException -> SendFailReason.CONNECT_FAILED
    is IOException -> SendFailReason.NETWORK_UNAVAILABLE
    else -> SendFailReason.UNKNOWN
}
