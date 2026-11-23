package com.fzm.chat.core.backup

import com.fzm.chat.core.utils.MMkvUtil

/**
 * 手机号登录用的本地 S2 分片存储。
 *
 * 账户管理给已有账号绑定手机号时，会把助记词拆成 S1/S2：
 * - S1 上传服务器（/api/v1/store-shard）
 * - S2 存在本地，同一设备手机号登录可直接组合出助记词
 *
 * 换设备登录时仍由用户手动输入 S2。
 */
object PhoneShardStore {

    private const val PREFIX = "phone_shard_"

    fun save(phone: String, shard: String) {
        MMkvUtil.encode(PREFIX + phone, shard)
    }

    fun get(phone: String): String {
        return MMkvUtil.decodeString(PREFIX + phone, "")
    }

    fun clear(phone: String) {
        MMkvUtil.removeKey(PREFIX + phone)
    }
}
