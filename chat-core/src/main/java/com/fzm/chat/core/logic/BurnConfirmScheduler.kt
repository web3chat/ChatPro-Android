package com.fzm.chat.core.logic

import com.fzm.chat.core.data.AppPreference
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.po.BurnPendingConfirm
import com.fzm.chat.core.repo.ChatRepository
import com.zjy.architecture.data.Result
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
     * @return true 表示已确认（burned=true）
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
     * @return true=已处理（私聊已出队，或群聊 burned=true；群聊仍需等信令出队），
     *         false=接口成功但群聊 burned=false，null=失败（保留队列等待补发）
     */
    private suspend fun confirmOnce(logId: Long, targetId: String, type: Int): Boolean? {
        return try {
            val repo = rootScope.get<ChatRepository>()
            when (val result = repo.burnConfirm(targetId, logId, type)) {
                is Result.Success -> {
                    val burned = result.data.burned
                    android.util.Log.d(TAG, "confirm SUCCESS: logId=$logId type=$type burned=$burned")
                    if (type == 0) {
                        // 私聊保持原行为：确认成功即出队，不再重复补确认
                        drop(logId)
                        true
                    } else if (burned) {
                        // 群聊：burned=true 只表示本端收行已删，仍要等信令才能出队
                        android.util.Log.d(TAG, "group burned=true, waiting for signal: logId=$logId")
                        burned
                    } else {
                        false
                    }
                }
                is Result.Loading -> null
                is Result.Error -> {
                    android.util.Log.e(
                        TAG,
                        "confirm ERROR: logId=$logId targetId=$targetId type=$type msg=${result.error().message}"
                    )
                    null
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
        GlobalScope.launch(Dispatchers.IO) {
            replay()
        }
    }
}
