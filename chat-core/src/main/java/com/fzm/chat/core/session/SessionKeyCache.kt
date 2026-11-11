package com.fzm.chat.core.session

/**
 * 会话级密码缓存（**只存在内存里**，不落盘，App 进程被杀即失效）
 *
 * 背景：**领**链上红包需要用密码解出私钥，每次拆红包都弹密码框太繁琐；
 * 用户确认可以接受「App 存活期间记住密码，杀掉进程后需要重新输入」。
 *
 * 使用范围（重要）：
 * - **只有领红包**（RedPacketDialogFragment）会读写这里的缓存
 * - 发红包、导出私钥/助记词等敏感入口仍然每次都输密码/校验
 *
 * 失效时机：切换钱包账户（地址不一致或重新登录）与退出登录时立即清空
 */
object SessionKeyCache {

    @Volatile
    private var address: String? = null

    @Volatile
    private var password: String? = null

    /**
     * 取当前会话缓存的密码；没有缓存或地址不一致（切了账号）时返回 null
     */
    fun get(currentAddress: String?): String? {
        val pwd = password ?: return null
        if (currentAddress.isNullOrEmpty() || currentAddress != address) return null
        return pwd
    }

    fun put(currentAddress: String?, pwd: String) {
        if (currentAddress.isNullOrEmpty() || pwd.isEmpty()) return
        address = currentAddress
        password = pwd
    }

    fun clear() {
        address = null
        password = null
    }
}
