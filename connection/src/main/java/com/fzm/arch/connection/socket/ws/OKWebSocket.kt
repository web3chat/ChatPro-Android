package com.fzm.arch.connection.socket.ws

import android.os.*
import chat33.comet.Socket
import com.fzm.arch.connection.protocol.Protocols
import com.fzm.arch.connection.protocol.Protocols.HEADER_LENGTH
import com.fzm.arch.connection.protocol.Protocols.PACKAGE_LENGTH
import com.fzm.arch.connection.socket.BaseChatSocket
import com.fzm.arch.connection.socket.SocketState
import com.fzm.arch.connection.socket.socketIdentifier
import com.fzm.arch.connection.utils.print
import com.fzm.arch.connection.utils.toPackage
import com.fzm.arch.connection.utils.urlKey
import com.zjy.architecture.exception.ApiException
import com.zjy.architecture.util.logE
import com.zjy.architecture.util.logI
import com.zjy.architecture.util.logV
import okhttp3.*
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author zhengjy
 * @since 2020/12/07
 * Description:
 */
class OKWebSocket(
    private val url: String,
    private val client: OkHttpClient
) : BaseChatSocket() {

    private lateinit var webSocket: WebSocket

    private val thread = HandlerThread("OKWebSocket-thread-${num.incrementAndGet()}")

    // 子线程Handler
    private val mHandler: DispatchHandler

    // 主线程Handler
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    private val init = AtomicBoolean(true)
    private val needReconnect = AtomicBoolean(true)

    /** 本次发起连接的时间点（[SystemClock.elapsedRealtime]），用于打印连接耗时 */
    private var connectStartAt = 0L

    private val request = Request.Builder().url(url).build()
    private val listener = ChatWebSocketListener(url)

    private val seq = AtomicInteger(0)

    init {
        thread.start()
        mHandler = DispatchHandler(this, thread.looper)
    }

    override fun connect() {
        if (init.get()) {
            connectStartAt = SystemClock.elapsedRealtime()
            logI("开始连接，$url")
            webSocket = client.newWebSocket(request, listener)
        } else {
            logI("重连，$url")
            reconnect.run()
        }
    }

    override fun onForeground(foreground: Boolean) {
        if (foreground) {
            if (!isAlive) {
                // 从后台进入app，如果处于断开状态，则重置重连次数并重连
                mainHandler.removeCallbacks(reconnect)
                reconnectTimes = 0
                tryReconnect()
            }
        }
    }

    override fun release() {
        resetSocket(true)
    }

    override fun send(message: ByteArray, extra: Bundle?): String? {
        if (extra == null) return null
        val resendSeq = extra.getInt(Protocols.SEQ)
        val newSeq = if (resendSeq != 0) resendSeq else seq.incrementAndGet()
        val proto = Socket.Proto.newBuilder()
            .setVer(Protocols.PROTOCOL_VER)
            .setOp(extra.getInt(Protocols.OPTION))
            .setSeq(newSeq)
            .setAck(extra.getInt(Protocols.ACK))
            .setBody(com.google.protobuf.ByteString.copyFrom(message))
            .build()
        extra.putInt(Protocols.SEQ, newSeq)
        val identifier = socketIdentifier(toString(), newSeq)
        val data = proto.toPackage()
        if (_state != SocketState.ESTABLISHED) {
            return null
        }
        webSocket.send(ByteString.of(data, 0, data.size))
        proto.print("[${url.urlKey()}] #${toString().let { it.substring(it.length - 5) }} Send ")
        return identifier
    }

    private val reconnect: Runnable = Runnable {
        if (_state == SocketState.INITIAL || _state == SocketState.CONNECTING || _state == SocketState.ESTABLISHED) {
            return@Runnable
        }
        _state = SocketState.CONNECTING
        connectStartAt = SystemClock.elapsedRealtime()
        logI("开始连接（第 $reconnectTimes 次重连），$url")

        webSocket = client.newWebSocket(request, listener)
    }

    private fun tryReconnect() {
        if (needReconnect.get()) {
            reconnectTimes++
            when {
                reconnectTimes == 1 -> mainHandler.post(reconnect)
                reconnectTimes < 10 -> mainHandler.postDelayed(reconnect, 1000)
                reconnectTimes < 30 -> mainHandler.postDelayed(reconnect, 5000)
                reconnectTimes < 50 -> mainHandler.postDelayed(reconnect, 15000)
                else -> mainHandler.postDelayed(reconnect, 60000)
            }
            // [MAX_RECONNECT_PERMITTED]次重连不上就通知状态变化
            if (reconnectTimes > MAX_RECONNECT_PERMITTED) _stateLive.postValue(SocketState.CONNECTING)
        }
    }

    private fun resetSocket(appClose: Boolean) {
        _state = SocketState.DISCONNECTED
        if (appClose) {
            needReconnect.set(false)
            mainHandler.removeCallbacks(reconnect)
            // app主动断开连接
            webSocket.close(1000, "app close")
            webSocket.cancel()
            listeners.clear()
            thread.quitSafely()
            _stateLive.postValue(SocketState.DISCONNECTED)
        } else {
            tryReconnect()
        }
    }

    override fun toString(): String {
        return "OKWebSocket@${System.identityHashCode(webSocket)}"
    }

    inner class ChatWebSocketListener(private val url: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val cost = if (connectStartAt > 0) SystemClock.elapsedRealtime() - connectStartAt else 0L
            logI("连接成功，$url，耗时${cost}ms，protocol=${response.protocol()}，重连次数=$reconnectTimes")
            connectStartAt = 0L
            seq.set(0)
            init.compareAndSet(true, false)
            // 修改链接状态
            _state = SocketState.ESTABLISHED
            _stateLive.postValue(SocketState.ESTABLISHED)
            mainHandler.removeCallbacks(reconnect)
            // 重置重连次数
            reconnectTimes = 0
            needReconnect.set(true)
            mHandler.obtainMessage(OPEN_CALLBACK, url).sendToTarget()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // 去除头部数据
            val payload = bytes.substring(PACKAGE_LENGTH + HEADER_LENGTH)
            mHandler.obtainMessage(MSG_CALLBACK, url to payload).sendToTarget()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            logI("连接关闭中，$url，$code, $reason")
            resetSocket(false)
            mHandler.obtainMessage(CLOSE_CALLBACK, Triple(url, code, reason)).sendToTarget()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            logI("连接关闭，$url，$code, $reason")
            resetSocket(false)
            mHandler.obtainMessage(CLOSE_CALLBACK, Triple(url, code, reason)).sendToTarget()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            logE("连接异常，$url，重连次数=$reconnectTimes，${t.javaClass.simpleName}: ${t.message}")
            connectStartAt = 0L
            resetSocket(false)
            mHandler.obtainMessage(CLOSE_CALLBACK, Triple(url, 0, t.message ?: "")).sendToTarget()
        }
    }

    internal class DispatchHandler(private val socket: OKWebSocket, looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_CALLBACK -> {
                    val pair = msg.obj as Pair<*, *>
                    val byteString = pair.second as ByteString
                    logV("收到消息，$byteString")
                    val array = byteString.toByteArray()
                    for (listener in socket.listeners) {
                        listener.onMessage(pair.first as String, array)
                    }
                }
                OPEN_CALLBACK -> {
                    for (listener in socket.listeners) {
                        listener.onOpen(msg.obj as String)
                    }
                }
                CLOSE_CALLBACK -> {
                    val triple = msg.obj as Triple<*, *, *>
                    for (listener in socket.listeners) {
                        listener.onClose(
                            triple.first as String,
                            ApiException(triple.second as Int, triple.third as String)
                        )
                    }
                }
            }
        }
    }

    companion object {

        private val num = AtomicInteger(0)
    }
}