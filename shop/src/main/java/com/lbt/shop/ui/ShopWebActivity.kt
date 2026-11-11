package com.lbt.shop.ui

import com.alibaba.android.arouter.facade.annotation.Route
import com.fzm.chat.biz.ui.WebContainerActivity
import com.fzm.chat.biz.webview.BizWebView
import com.fzm.chat.router.shop.ShopModule
import com.lbt.shop.ShopJsBridge

/**
 * @author zhengjy
 * @since 2021/10/18
 * Description:
 */
@Route(path = ShopModule.WEB)
class ShopWebActivity : WebContainerActivity() {

    override fun setupWebView(bizWebView: BizWebView) {
        bizWebView.mWebView.addJavascriptObject(ShopJsBridge(bizWebView), null)
    }
}