package com.fzm.chat.core.data.bean

import java.io.Serializable

/**
 * [POST /app/record/clean-private] 成功时 data 字段
 */
data class CleanPrivateData(
    val deletedCount: Long = 0
) : Serializable

/**
 * [POST /app/record/auto-delete/set]、[POST /app/record/auto-delete/get] 成功时 data 字段
 */
data class AutoDeleteSettingData(
    val option: Int = 0,
    val updatedBy: String = "",
    val updatedAt: Long = 0L
) : Serializable
