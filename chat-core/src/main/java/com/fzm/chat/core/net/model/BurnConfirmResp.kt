package com.fzm.chat.core.net.model

/**
 * [POST /app/record/burn-confirm] 成功时 data 字段
 */
data class BurnConfirmResp(
    /**
     * 本次 mid
     */
    val logId: Long = 0,
    /**
     * true：本次删了；false：幂等（已经焚过或 mid 已不存在）
     */
    val burned: Boolean = false
)
