package com.lbt.shop.di

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.template.IProvider
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.biz.ui.DeepLinkHelper
import com.fzm.chat.router.shop.ShopModule
import com.fzm.shop.R
import com.lbt.shop.ShopConfig
import com.lbt.shop.net.ShopRepository
import com.lbt.shop.net.ShopService
import com.lbt.shop.ui.ShopViewModel
import com.zjy.architecture.di.Injector
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import retrofit2.Retrofit

/**
 * @author zhengjy
 * @since 2021/10/23
 * Description:
 */
@Route(path = ShopModule.INJECTOR)
class ShopInjector : Injector, IProvider {

    override fun inject() = module {
        factory { ShopRepository(get<Retrofit>().create(ShopService::class.java)) }
        viewModel { ShopViewModel(get(), get()) }
    }

    override fun init(context: Context?) {
        DeepLinkHelper.registerWebUrl(
            ShopConfig.SHOP_WEB,
            ARouter.getInstance().build(ShopModule.WEB)
                .withBoolean("showTitle", true)
                .withBoolean("miniAppStyle", true)
                .withString("title", context?.getString(R.string.app_name).toString() + context?.getString(R.string.shop_title_suffix))
                .withTransition(0, 0)
        )
    }
}