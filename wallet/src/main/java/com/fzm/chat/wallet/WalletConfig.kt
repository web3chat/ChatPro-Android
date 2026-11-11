package com.fzm.chat.wallet

import com.zjy.architecture.util.AbstractConfig

/**
 * @author zhengjy
 * @since 2021/08/04
 * Description:
 */
object WalletConfig {

    private object WalletDev : AbstractConfig("wallet-dev.properties")
    private object WalletPro : AbstractConfig("wallet-pro.properties")

    private val config = WalletPro

    /**
     * 默认手续费
     */
    const val DEFAULT_FEE = 0.001

}