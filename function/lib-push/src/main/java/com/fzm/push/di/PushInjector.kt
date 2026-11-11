package com.fzm.push.di

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.template.IProvider
import com.fzm.chat.biz.base.AppConfig
import com.fzm.chat.router.push.PushModule
import com.fzm.push.PushConfig
import com.fzm.push.PushManager
import com.umeng.commonsdk.UMConfigure
import com.zjy.architecture.di.Injector
import com.zjy.architecture.util.ProcessUtil
import org.koin.dsl.module

/**
 * @author zhengjy
 * @since 2021/04/02
 * Description:
 */
@Route(path = PushModule.INJECTOR)
class PushInjector : Injector, IProvider {

    override fun inject() = module {

    }

    override fun init(context: Context) {
        // 友盟预初始化：U-APM 崩溃监控需要尽早启动，避免漏采冷启动阶段的闪退
        // （完整初始化在 PushManager.init 中通过 UMConfigure.init 完成）
        // 注意：preInit 在 Application.onCreate 主线程执行，异常必须兜住，避免启动直接闪退
        try {
            UMConfigure.preInit(context, PushConfig.UMENG_APP_KEY, AppConfig.APP_NAME_EN)
        } catch (e: Throwable) {
            android.util.Log.e("push", "UMConfigure.preInit failed", e)
        }
        // 获取当前包名
        val packageName = context.packageName
        // 获取当前进程名
        val processName = ProcessUtil.getCurrentProcessName(context)
        if (processName == null || processName == packageName) {
            Thread { PushManager.init(context) }.start()
        } else {
            PushManager.init(context)
        }
    }
}