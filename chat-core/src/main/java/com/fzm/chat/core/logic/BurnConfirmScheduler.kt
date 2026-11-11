package com.fzm.chat.core.logic

import com.fzm.chat.core.data.AppPreference
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
 */
object BurnConfirmScheduler {

    fun start() {
        GlobalScope.launch(Dispatchers.IO) {
            delay(1500)
            replay()
        }
    }

    /**
     * 正文开始展示时落库（收方）
     */
    suspend fun markOpened(logId: Long, targetId: String) {
        if (logId == 0L || targetId.isEmpty()) return
        ChatDatabaseProvider.provide().burnPendingDao().insert(
            BurnPendingConfirm(logId, targetId, System.currentTimeMillis())
        )
    }

    fun confirmAsync(logId: Long, targetId: String) {
        GlobalScope.launch(Dispatchers.IO) {
            confirm(logId, targetId)
        }
    }

    /**
     * 关闭详情 / 补发时调用 burn-confirm，成功则删记录
     */
    suspend fun confirm(logId: Long, targetId: String): Boolean {
        if (logId == 0L || targetId.isEmpty()) return false
        android.util.Log.d("BurnFlow", "confirm: logId=$logId targetId=$targetId")
        return try {
            val repo = rootScope.get<ChatRepository>()
            when (val result = repo.burnConfirm(targetId, logId)) {
                is Result.Success -> {
                    android.util.Log.d("BurnFlow", "confirm SUCCESS: logId=$logId burned=${result.data.burned}")
                    ChatDatabaseProvider.provide().burnPendingDao().delete(logId)
                    true
                }
                is Result.Loading -> {
                    android.util.Log.d("BurnFlow", "confirm LOADING: logId=$logId")
                    false
                }
                is Result.Error -> {
                    android.util.Log.e("BurnFlow", "confirm ERROR: logId=$logId targetId=$targetId msg=${result.error().message}")
                    false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BurnFlow", "confirm EXCEPTION: logId=$logId", e)
            logE("BurnConfirmScheduler", e.message)
            false
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
        items.forEach { confirm(it.logId, it.targetId) }
    }
}
