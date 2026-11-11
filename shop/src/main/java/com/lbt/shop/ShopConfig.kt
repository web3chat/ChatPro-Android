package com.lbt.shop

import com.fzm.chat.core.BuildConfig
import com.zjy.architecture.util.AbstractConfig

/**
 * @author zhengjy
 * @since 2021/10/18
 * Description:
 */
object ShopConfig {

    private object ShopDev : AbstractConfig("shop-dev.properties")
    private object ShopPro : AbstractConfig("shop-pro.properties")

    val config: AbstractConfig = ShopPro

    val SHOP_WEB = config["SHOP_WEB"]
}