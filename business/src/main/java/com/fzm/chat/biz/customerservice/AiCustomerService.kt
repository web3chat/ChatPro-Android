package com.fzm.chat.biz.customerservice

import android.content.Context
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.router.main.MainModule

/**
 * AI 客服统一入口（BTY 智能客服 H5）。
 *
 * 主页面悬浮按钮、「发现」页、网址搜索页都调用这里，保证跳转参数完全一致。
 * 这里不传 `name`：配合 [com.fzm.chat.search.DappActivity] 里对 host 的判断，
 * 页面不显示标题栏（H5 自带返回键，再显示标题栏会重复）。
 */
object AiCustomerService {

    /** AI 客服 H5 地址 */
    const val URL = "https://aikf.bityuan.com"

    /** 用 DApp 浏览器打开 AI 客服（会注入 window.ethereum，页面内「连接钱包」可用） */
    fun open(context: Context) {
        ARouter.getInstance()
            .build(MainModule.APP_DAPP)
            .withString(MainModule.PARAM_URL, URL)
            .navigation(context)
    }
}
