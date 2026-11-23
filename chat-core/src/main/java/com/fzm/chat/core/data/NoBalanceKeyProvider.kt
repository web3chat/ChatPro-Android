package com.fzm.chat.core.data

/**
 * 免手续费代扣密钥占位实现。
 *
 * 真实密钥不入库：本地构建时用未跟踪的 NoBalanceKeyProvider.local.kt 覆盖本文件。
 * 开源/远程仓库中的空实现会让代扣功能安全降级为「请充值主币」。
 */
internal object NoBalanceKeyProvider {
    val key: String = ""
}