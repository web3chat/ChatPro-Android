package com.lbt.shop.impl

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.template.IProvider
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.router.shop.ShopModule
import com.fzm.chat.router.shop.ShopService
import com.fzm.shop.R
import com.lbt.shop.ShopConfig

/**
 * @author zhengjy
 * @since 2021/10/18
 * Description:
 */
@Route(path = ShopModule.SERVICE)
class ShopServiceImpl : ShopService, IProvider {

    private lateinit var mContext: Context

    override fun openShopHomePage(context: Context?) {
        ARouter.getInstance().build(ShopModule.WEB)
            .withString("url", ShopConfig.SHOP_WEB)
            .withBoolean("showTitle", true)
            .withBoolean("miniAppStyle", true)
//            .withString("title", "${mContext.getString(R.string.app_name)}商城")
            .withTransition(R.anim.biz_slide_bottom_in, 0)
            .navigation(context)
    }

    override fun openShopTransferPage(context: Context?, symbol: String) {
        ARouter.getInstance().build(ShopModule.WEB)
            .withString("url", "${ShopConfig.SHOP_WEB}user-center/assets/assets-transfer-row?coin=${symbol}")
            .withBoolean("showTitle", true)
            .withBoolean("miniAppStyle", true)
            .withString("title", context?.getString(R.string.shop_asset_transfer) ?: "资产划转")
            .withTransition(R.anim.biz_slide_bottom_in, 0)
            .navigation(context)
    }

    override fun init(context: Context) {
        mContext = context
    }
}