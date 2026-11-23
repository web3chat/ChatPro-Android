package com.fzm.chat.core.service

import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import com.fzm.chat.core.R
import com.fzm.arch.connection.logic.MessageDispatcher
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.session.UserInfo
import com.zjy.architecture.util.ActivityUtils
import com.zjy.architecture.util.immutableFlag
import com.zjy.architecture.util.logV
import org.koin.android.ext.android.inject

/**
 * @author zhengjy
 * @since 2021/01/12
 * Description:
 */
class MessageService : Service(), ActivityUtils.OnAppStateChangedListener {

    companion object {

        private const val NOTICE_ID = -1
    }

    private val delegate by inject<LoginDelegate>()
    private val dispatcher by inject<MessageDispatcher>()
    private val observer: Observer<UserInfo> = Observer { info ->
        if (!info.isLogin()) {
            stopSelf()
        }
    }

    private var mReceiverTag = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logV("MessageService", "onStartCommand")
        val startForeground = intent?.getBooleanExtra("startForeground", false) ?: false
        if (startForeground) {
            startForeground()
        }
        dispatcher.loop()
        delegate.current.observeForever(observer)
        ActivityUtils.addOnAppStateChangedListener(this)
        if (!mReceiverTag) {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            registerReceiver(mScreenReceiver, filter)
            mReceiverTag = true
        }
        return START_STICKY
    }

    /**
     * 提升为前台服务。
     *
     * Android 12（API 31）起，后台应用不允许调用 startForeground()，
     * 否则抛 ForegroundServiceStartNotAllowedException。
     * 例如锁屏收到 ACTION_SCREEN_OFF 时应用已在后台，这里如果再抛异常会直接导致
     * 「Error receiving broadcast」崩溃，因此必须兜底。
     */
    private fun startForeground() {
        val builder = NotificationCompat.Builder(this, "notification")
        builder.setSmallIcon(R.drawable.ic_notification)
        val intent = Intent().apply {
            component = ComponentName(packageName, "com.fzm.chat.app.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT.immutableFlag
        )
        builder.setContentIntent(contentIntent)
        builder.setAutoCancel(true)
        try {
            startForeground(NOTICE_ID, builder.build())
        } catch (e: Exception) {
            // Android 12+ 后台禁止提升前台服务；忽略即可，服务本身仍在运行，
            // 等下次回到前台再提升。不能让它冒泡到 onReceive 造成崩溃。
            logV(
                "MessageService",
                "startForeground ignored: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logV("MessageService", "onDestroy")
        stopForeground(true)
        if (mReceiverTag) {
            unregisterReceiver(mScreenReceiver)
            mReceiverTag = false
        }
        delegate.current.removeObserver(observer)
    }

    private val mScreenReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            // 广播里任何异常都会变成「Error receiving broadcast」直接崩溃，这里统一兜底
            try {
                when {
                    Intent.ACTION_SCREEN_OFF == action -> {
                        // 息屏
                        logV("MessageService", "startForeground")
                        startForeground()
                    }
                    Intent.ACTION_SCREEN_ON == action -> {
                        // 亮屏
                        logV("MessageService", "stopForeground")
                        stopForeground(true)
                    }
                    Intent.ACTION_USER_PRESENT == action -> {
                        // 解锁
                        logV("MessageService", "stopForeground")
                        stopForeground(true)
                    }
                }
            } catch (e: Exception) {
                logV(
                    "MessageService",
                    "screen receiver error: action=$action, ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    override fun onLeaveApp() {

    }

    override fun onResumeApp() {
        stopForeground(true)
    }
}