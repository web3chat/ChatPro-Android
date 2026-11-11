package com.fzm.chat.utils

import com.fzm.chat.MMkvUtil

/**
 * 使用 MMKV 缓存每个账户的头像和昵称，以用户地址(address)作为区分。
 * 写入时机：AccountFragment 收到 loginDelegate.current 回调时
 * 读取时机：AccountManagerActivity 打开时
 */
object AccountProfileCache {

    private const val KEY_AVATAR_PREFIX = "account_profile_avatar_"
    private const val KEY_NICKNAME_PREFIX = "account_profile_nickname_"

    fun save(address: String, avatar: String, nickname: String) {
        if (address.isBlank()) return
        MMkvUtil.encode(KEY_AVATAR_PREFIX + address, avatar)
        MMkvUtil.encode(KEY_NICKNAME_PREFIX + address, nickname)
    }

    fun getAvatar(address: String): String {
        return MMkvUtil.decodeString(KEY_AVATAR_PREFIX + address)
    }

    fun getNickname(address: String): String {
        return MMkvUtil.decodeString(KEY_NICKNAME_PREFIX + address)
    }
}
