package com.fzm.chat.core.net.model

/**
 * 批量撤回消息响应
 *
 * @author zhengjy
 * @since 2026/07/02
 */
data class RevokeBatchResp(
    val total: Int,
    val successCount: Int,
    val failedCount: Int,
    val succeeded: List<Long>,
    val failed: List<RevokeBatchFailItem>
)

data class RevokeBatchFailItem(
    val logId: Long,
    val reason: String
)
