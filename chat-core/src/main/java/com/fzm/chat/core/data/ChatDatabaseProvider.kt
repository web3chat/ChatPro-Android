package com.fzm.chat.core.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.zjy.architecture.di.rootScope

/**
 * @author zhengjy
 * @since 2020/12/04
 * Description:
 */
class ChatDatabaseProvider {

    companion object {

        @Volatile
        private var sInstance: ChatDatabase? = null

        init {
            // 保证在主线程执行
            Handler(Looper.getMainLooper()).post {
                AppPreference.address.observeForever { address ->
                    // 地址变化（包括空 -> 非空、A -> B）必须换库；
                    // 只在 address 为空时重置，会残留上一个账号/".db" 实例，导致聊天记录空白。
                    val expected = "${address}.db"
                    if (sInstance != null && ChatDatabase.dbName != expected) {
                        synchronized(this) {
                            if (sInstance != null && ChatDatabase.dbName != expected) {
                                runCatching { sInstance?.close() }
                                sInstance = null
                            }
                        }
                    }
                }
            }
        }

        private val context = rootScope.get<Context>()

        fun provide(): ChatDatabase {
            val address = AppPreference.ADDRESS
            val expected = "${address}.db"
            if (sInstance == null || ChatDatabase.dbName != expected) {
                synchronized(this) {
                    if (sInstance == null || ChatDatabase.dbName != expected) {
                        runCatching { sInstance?.close() }
                        sInstance = ChatDatabase.build(context, address)
                        android.util.Log.d(
                            "ChatDB",
                            "provide address=$address db=${ChatDatabase.dbName}"
                        )
                    }
                }
            }
            return sInstance!!
        }

        /**
         * 关闭当前数据库实例（删除账户、切换账号前调用，保证数据库文件可被删除）
         */
        fun close() {
            synchronized(this) {
                runCatching { sInstance?.close() }
                sInstance = null
            }
        }
    }
}