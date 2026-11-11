package com.fzm.chat.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.biz.ui.DeepLinkHelper
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.router.app.AppModule
import com.fzm.chat.router.main.MainModule
import com.zjy.architecture.base.BaseActivity
import com.zjy.architecture.base.instance
import com.zjy.architecture.ext.notchSupport
import com.zjy.architecture.util.ActivityUtils

/**
 * @author zhengjy
 * @since 2020/08/31
 * Description:
 */
class SplashActivity : BaseActivity() {

    override val layoutId: Int = R.layout.activity_splash

    override fun initView() {
        // 处理 H5 唤醒
        handleH5Intent(intent)
    }

    // 新Intent进来时触发（比如App已在后台）
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            handleH5Intent(intent)
        }
    }

    /**
     * 处理 H5 跳转：解析地址和id，跳转到ChatActivity
     *
     * 场景1：App 后台存活 → MainActivity 已存在 → 直接跳转 ChatActivity
     * 场景2：App 被杀死 → MainActivity 不存在 → 保存参数到 DeepLinkHelper，
     *        走正常启动流程（登录后由 MainActivity 消费参数跳转）
     */
    private fun handleH5Intent(intent: Intent) {
        val uri = intent.data ?: return

        // 判断是否是跳转到聊天页的协议
        if ("chatpro" == uri.scheme && "chat" == uri.host) {
            // 获取参数
            val address: String? = uri.getQueryParameter("address") // String 地址
            val idStr: String? = uri.getQueryParameter("id")
            val id: Int = idStr?.toIntOrNull() ?: -1 // Int id

            if (ActivityUtils.isActivityExist("com.fzm.chat.app.MainActivity")) {
                // 场景1：App 在后台存活，直接跳转 ChatActivity
                ARouter.getInstance().build(MainModule.CHAT)
                    .withString("address", address)
                    .withInt("channelType", ChatConst.PRIVATE_CHANNEL)
                    .withFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .navigation()
                finish()
            } else {
                // 场景2：App 被杀死，保存参数等登录后由 MainActivity 处理
                DeepLinkHelper.savePendingChatParams(address, ChatConst.PRIVATE_CHANNEL)
                // 不 finish，让 initData() 正常走 MainActivity 启动流程
            }
        }
    }

        override fun setSystemBar() {
            notchSupport(window)
        }

        override fun initData() {
            // 停留片刻展示启动页（之前 100ms 太快）；不等待 checkUpdate 网络回调，弱网下不会额外卡启动页
            // （更新检查已移至 MainActivity 首次 onResume）
            window?.decorView?.postDelayed({
                if (!ActivityUtils.isActivityExist("com.fzm.chat.app.MainActivity")) {
                    ARouter.getInstance().build(AppModule.MAIN)
                        .withTransition(R.anim.biz_fade_in, R.anim.biz_fade_out)
                        .navigation(instance)
                }
                finish()
            }, SPLASH_DELAY)
        }

        companion object {
            /** 启动页最短展示时长（毫秒） */
            private const val SPLASH_DELAY = 1500L
        }

        override fun setEvent() {

        }
}