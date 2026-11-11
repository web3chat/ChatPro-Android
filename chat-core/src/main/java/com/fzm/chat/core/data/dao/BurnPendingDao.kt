package com.fzm.chat.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fzm.chat.core.data.po.BurnPendingConfirm

/**
 * 阅后即焚待确认队列 DAO
 */
@Dao
interface BurnPendingDao {

    /**
     * 打开详情即落库（同一消息重复打开用 REPLACE 覆盖）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BurnPendingConfirm)

    /**
     * burn-confirm 成功后删除；已收到 burned 信令也删除
     */
    @Query("DELETE FROM burn_pending_confirm WHERE logId = :logId")
    suspend fun delete(logId: Long)

    /**
     * 启动补发扫描
     */
    @Query("SELECT * FROM burn_pending_confirm")
    suspend fun getAll(): List<BurnPendingConfirm>
}
