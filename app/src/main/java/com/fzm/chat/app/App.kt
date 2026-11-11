package com.fzm.chat.app

import android.app.Application
import android.content.Intent
import com.fzm.chat.biz.base.AppConfig
import com.fzm.chat.biz.base.GlobalConfig
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.logic.ServerManager
import com.fzm.chat.router.biz.BizModule
import com.fzm.chat.router.biz.BizService
import com.fzm.chat.router.core.CoreModule
import com.fzm.chat.router.live.LiveModule
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.oa.OAModule
import com.fzm.chat.router.push.PushModule
import com.tencent.bugly.crashreport.CrashReport
import com.tencent.bugly.crashreport.CrashReport.UserStrategy
import com.fzm.chat.router.redpacket.RedPacketModule
import com.fzm.chat.router.route
import com.fzm.chat.router.rtc.RtcModule
import com.fzm.chat.router.shop.ShopModule
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.biz.utils.LocaleUtils
import com.zjy.architecture.Arch
import com.zjy.architecture.ext.versionName
import com.zjy.architecture.util.ProcessUtil

/**
 * @author zhengjy
 * @since 2020/12/04
 * Description:
 */
class App : Application() {

    private val bizService by route<BizService>(BizModule.SERVICE)

    override fun onCreate() {
        super.onCreate()

        // 崩溃展示进程（:crash）：不初始化任何 SDK，避免二次崩溃
        val currentProcess = ProcessUtil.getCurrentProcessName(this)
        if (currentProcess != null && currentProcess.endsWith(":crash")) {
            return
        }

        Arch.init(this, GlobalConfig.DEBUG, GlobalConfig.LOG_ENC_KEY, arrayOf(
            BizModule.INJECTOR, CoreModule.INJECTOR, MainModule.INJECTOR,
            RtcModule.INJECTOR, WalletModule.INJECTOR, RedPacketModule.INJECTOR, OAModule.INJECTOR,
            ShopModule.INJECTOR, PushModule.INJECTOR
        ))

        // 崩溃兜底：必须在 Arch.init 之后注册！
        // ChatConfig 的静态初始化依赖 Arch 上下文（AbstractConfig 构造需要 Arch.context），
        // 提前访问会抛 IllegalStateException: Please init Arch first。
        // 代价：Arch.init 期间的崩溃捕获不到，属可接受范围。
        setupCrashHandler()

        //setupBugly()
        // 加载上次服务器地址配置
        ServerManager.loadLastServer()

        // 初始化多语言设置
        LocaleUtils.applyLanguage(this)

        bizService?.fetchModuleState()
        bizService?.fetchServerList()

        System.loadLibrary("TrustWalletCore")
    }

    /**
     * 全局崩溃兜底（CRASH_DIALOG=true 时生效）
     *
     * 主进程崩溃时：落盘堆栈 → 拉起 [CrashReportActivity]（:crash 独立进程）展示错误，
     * 用户可截图/复制堆栈；子进程（如 :channel）崩溃仍交给系统默认处理。
     */
    private fun setupCrashHandler() {
        // 兜底机制本身绝不能影响启动：任何异常只记录，不抛出
        try {
            if (!ChatConfig.CRASH_DIALOG) return
            val origin = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                var handled = false
                try {
                    val current = ProcessUtil.getCurrentProcessName(this)
                    val isMainProcess = current == null || current == packageName
                    if (isMainProcess) {
                        val stack = android.util.Log.getStackTraceString(throwable)
                        // 落盘，便于事后排查
                        try {
                            val dir = java.io.File(filesDir, "crash")
                            if (!dir.exists()) dir.mkdirs()
                            val time = java.text.SimpleDateFormat(
                                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                            ).format(java.util.Date())
                            java.io.File(dir, "last_crash.txt").writeText("$time\n$stack")
                        } catch (_: Throwable) {
                        }
                        val intent = Intent(this, CrashReportActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            )
                            putExtra(CrashReportActivity.EXTRA_CRASH, stack.take(20000))
                        }
                        startActivity(intent)
                        handled = true
                        // 给错误页启动留出时间后结束主进程
                        Thread {
                            try {
                                Thread.sleep(1500)
                            } catch (_: Exception) {
                            }
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }.start()
                    }
                } catch (_: Throwable) {
                }
                if (!handled) {
                    origin?.uncaughtException(thread, throwable)
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("CrashGuard", "崩溃兜底注册失败：${e.message}")
        }
    }

    private fun setupBugly() {
        // 获取当前包名
        val packageName = packageName
        // 获取当前进程名
        val processName = ProcessUtil.getCurrentProcessName(this)
        val strategy = UserStrategy(this).apply {
            isUploadProcess = processName == null || processName == packageName
            appChannel = "default"
            appVersion = versionName
            appPackageName = packageName
        }
        // 初始化Bugly
        CrashReport.initCrashReport(applicationContext, "", GlobalConfig.DEBUG, strategy)
    }

    override fun onTerminate() {
        super.onTerminate()
        Arch.release()
    }
}