package com.fzm.chat.core.logic

import com.fzm.chat.core.data.AppPreference
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.po.BurnPendingConfirm
import com.fzm.chat.core.repo.ChatRepository
import com.zjy.architecture.data.Result
import com.zjy.architecture.exception.ApiException
import com.zjy.architecture.di.rootScope
import com.zjy.architecture.util.logE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 阅后即焚待确认队列：打开详情即落库，杀进程后启动补发 burn-confirm。
 *
 * 群聊要点（《群聊阅后即焚_App对接文档》）：
 * - confirm 必须带 `type=1`，`targetId` 是**群 id**；
 * - 只有收到本端 `SignalRevoke.reason=burned` 才能把气泡改成「已阅后焚毁」并删除队列，
 *   HTTP `result=0`（甚至 `burned=true`）都不能删；
 * - `burned=false` 时按 500ms / 1s / 2s 补确认，最多 3 次。
 */
object BurnConfirmScheduler {

    private const val TAG = "BurnFlow"

    /** `burned=false` 时的补确认间隔（文档要求） */
    private val RETRY_DELAYS = longArrayOf(500L, 1000L, 2000L)

    fun start() {
        GlobalScope.launch(Dispatchers.IO) {
            delay(1500)
            replay()
        }
    }

    /**
     * 正文开始展示时落库（收方）
     *
     * @param type 0 私聊 / 1 群聊
     */
    suspend fun markOpened(logId: Long, targetId: String, type: Int = 0) {
        if (logId == 0L || targetId.isEmpty()) return
        ChatDatabaseProvider.provide().burnPendingDao().insert(
            BurnPendingConfirm(logId, targetId, System.currentTimeMillis(), type)
        )
    }

    fun confirmAsync(logId: Long, targetId: String, type: Int = 0) {
        GlobalScope.launch(Dispatchers.IO) {
            confirm(logId, targetId, type)
        }
    }

    /**
     * 关闭详情 / 补发时调用 burn-confirm。
     *
     * @return true 表示已处理（burned=true 等信令，或错误码已出队），
     *         false 表示成功但 burned=false（会继续补确认）
     */
    suspend fun confirm(logId: Long, targetId: String, type: Int = 0): Boolean {
        if (logId == 0L || targetId.isEmpty()) return false
        android.util.Log.d(TAG, "confirm: logId=$logId targetId=$targetId type=$type")

        val first = confirmOnce(logId, targetId, type) ?: return false
        if (first) return true

        // burned=false：按文档 500ms / 1s / 2s 再补 3 次
        for (delayMs in RETRY_DELAYS) {
            delay(delayMs)
            val retried = confirmOnce(logId, targetId, type) ?: return false
            if (retried) return true
        }
        return false
    }

    /**
     * 单次请求
     *
     * @return true=已处理（burned=true 等信令，或 -10402/-10403 已出队），
     *         false=接口成功但 burned=false，null=失败（保留队列等待补发）
     */
    private suspend fun confirmOnce(logId: Long, targetId: String, type: Int): Boolean? {
        return try {
            val repo = rootScope.get<ChatRepository>()
            when (val result = repo.burnConfirm(targetId, logId, type)) {
                is Result.Success -> {
                    val burned = result.data.burned
                    android.util.Log.d(TAG, "confirm SUCCESS: logId=$logId type=$type burned=$burned")
                    if (burned) {
                        // 群聊/私聊都一样：HTTP 成功不能出队、不能改气泡，
                        // 必须等本端收到 SignalRevoke.reason=burned
                        android.util.Log.d(TAG, "burned=true, waiting for signal: logId=$logId")
                        true
                    } else {
                        // burned=false：服务端没删、没发信令，按 500ms/1s/2s 继续确认
                        false
                    }
                }
                is Result.Loading -> null
                is Result.Error -> {
                    val error = result.error()
                    val code = (error as? ApiException)?.code
                    android.util.Log.e(
                        TAG,
                        "confirm ERROR: logId=$logId targetId=$targetId type=$type code=$code msg=${error.message}"
                    )
                    if (code == -10402 || code == -10403) {
                        // 不是阅后即焚 / 无权确认，继续补发没有意义
                        drop(logId)
                        true
                    } else {
                        // 网络失败、-1000、未知错误：保留队列，等待回前台/再进会话补发
                        null
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "confirm EXCEPTION: logId=$logId", e)
            logE("BurnConfirmScheduler", e.message)
            null
        }
    }

    /**
     * 收到 burned 信令后清除待确认记录
     */
    suspend fun drop(logId: Long) {
        ChatDatabaseProvider.provide().burnPendingDao().delete(logId)
    }

    /**
     * 启动补发扫描（接口幂等）
     */
    suspend fun replay() {
        if (AppPreference.ADDRESS.isEmpty()) return
        val db = try {
            ChatDatabaseProvider.provide()
        } catch (e: Exception) {
            logE("BurnConfirmScheduler", e.message)
            return
        }
        val items = db.burnPendingDao().getAll()
        items.forEach { item ->
            // 升级前入队的老数据 type 默认为 0，按本地消息的 channelType 纠正，
            // 否则群聊补发会继续按私聊处理。
            val type = if (item.type == 0) {
                db.messageDao().getMessageByLogId(listOf(item.logId))
                    .firstOrNull()
                    ?.let { msg ->
                        if (msg.channelType == ChatConst.GROUP_CHANNEL) 1 else 0
                    } ?: 0
            } else {
                item.type
            }
            confirm(item.logId, item.targetId, type)
        }
    }

    /** 登录成功后补发一次（启动时可能还没设置地址） */
    fun replayAsync() {
        val now = System.currentTimeMillis()
        if (now - lastReplayAt < 1000L) return
        lastReplayAt = now
        GlobalScope.launch(Dispatchers.IO) {
            replay()
        }
    }

    @Volatile
    private var lastReplayAt = 0L
}
