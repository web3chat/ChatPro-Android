package com.fzm.chat.core.data.bean

data class CheckSE(
    val chataddress: String,
    val exists: Boolean,
    val is_updated: Boolean?,
    val phone: String,
    val version: Int?
)

