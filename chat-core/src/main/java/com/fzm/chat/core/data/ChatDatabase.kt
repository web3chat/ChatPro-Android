package com.fzm.chat.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fzm.chat.core.converter.*
import com.fzm.chat.core.data.ChatDatabase.Companion.BIZ_DB_VERSION
import com.fzm.chat.core.data.dao.*
import com.fzm.chat.core.data.model.SearchHistory
import com.fzm.chat.core.data.po.*
import com.fzm.chat.core.session.UserInfo
import com.tencent.wcdb.room.db.WCDBOpenHelperFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * @author zhengjy
 * @since 2020/08/06
 * Description:
 */
@Database(
    entities = [
        // 本地用户信息
        UserInfo::class,
        // 用户信息
        FriendUserPO::class,
        // 群信息
        GroupInfo::class,
        // 群成员信息
        GroupUserPO::class,
        // 会话记录
        RecentSession::class,
        // 消息数据库
        MessagePO::class,
        // 搜索记录
        SearchHistory::class,
        // 消息数据全文搜索表
        MessageFtsPO::class,
        // 企业用户信息
        CompanyUserPO::class,
        // 企业信息
        CompanyInfoPO::class,
        // 红包消息对应表
        RedPacketMessage::class,
        // 消息关注用户表
        MessageFocusUser::class,
        // 阅后即焚待确认队列
        BurnPendingConfirm::class,
    ],
    views = [
        MessageFocusView::class
    ],
    version = BIZ_DB_VERSION
)
@TypeConverters(
    value = [
        ServerToStringConverter::class,
        ServerListToStringConverter::class,
        ContentToStringConverter::class,
        ListToStringConverter::class,
        GroupUserToString::class,
        GroupUserListToStringConverter::class,
        SourceToStringConverter::class,
        DraftToStringConverter::class,
        MapConverter::class,
    ]
)
abstract class ChatDatabase : RoomDatabase() {

    companion object {
        /**
         * 数据库的版本号
         */
        const val BIZ_DB_VERSION = 12

        /**
         * 数据库名
         */
        private var _dbName: String = ""
        val dbName: String
            get() = _dbName

        fun build(context: Context, address: String): ChatDatabase {
            _dbName = "${address}.db"
            val factory = WCDBOpenHelperFactory()
                // 打开WAL以及读写并发，可以省略让Room决定是否要打开
                .writeAheadLoggingEnabled(true)
                .asyncCheckpointEnabled(true)
            return Room.databaseBuilder(context, ChatDatabase::class.java, _dbName)
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12
                )
                .addCallback(CALL_BACK)
                .build()
        }

        private val CALL_BACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS insert_user_update_friend AFTER INSERT ON user_info
                    BEGIN
                       INSERT OR REPLACE INTO friend_user(address, nickname, avatar, publicKey, flag, servers, groups, chainAddress, remark, searchKey) 
                       VALUES (new.address, new.nickname, new.avatar, new.publicKey, 0, new.servers, "[]", new.chainAddress, "", new.searchKey);
                    END;
                """)
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS update_user_update_friend AFTER UPDATE OF address, nickname, avatar, server, chainAddress ON user_info
                    BEGIN
                       INSERT OR REPLACE INTO friend_user(address, nickname, avatar, publicKey, flag, servers, groups, chainAddress, remark, searchKey) 
                       VALUES (new.address, new.nickname, new.avatar, new.publicKey, 0, new.servers, "[]", new.chainAddress, "", new.searchKey);
                    END;
                """)
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `company_user` (`depId` TEXT NOT NULL, `entId` TEXT NOT NULL, `id` TEXT NOT NULL, `leaderId` TEXT, `name` TEXT NOT NULL, `phone` TEXT, `email` TEXT, `position` TEXT, `role` INTEGER NOT NULL, `workplace` TEXT, `joinTime` INTEGER NOT NULL, PRIMARY KEY(`entId`, `id`))")
                database.execSQL("CREATE TABLE IF NOT EXISTS `company` (`id` TEXT NOT NULL, `avatar` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `imServer` TEXT NOT NULL, `nodeServer` TEXT NOT NULL, `oaServer` TEXT NOT NULL, `rootDepId` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `company_user` ADD COLUMN `entName` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `company_user` ADD COLUMN `depName` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `company_user` ADD COLUMN `isActivated` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_company_user_id` ON `company_user` (`id`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `recent_session` ADD COLUMN `atMessages` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `packet_message` (`msgId` TEXT NOT NULL, `packetId` TEXT, PRIMARY KEY(`msgId`))")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `group_info` ADD COLUMN `key` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `group_info` ADD COLUMN `publicName` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `recent_session` ADD COLUMN `draft` TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `chat_message` ADD COLUMN `ref_topic` INTEGER")
                database.execSQL("ALTER TABLE `chat_message` ADD COLUMN `ref_refId` INTEGER")
                database.execSQL("ALTER TABLE `company_user` ADD COLUMN `shortPhone` TEXT")
                database.execSQL("ALTER TABLE `group_info` ADD COLUMN `groupType` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `message_focus_user` (`logId` INTEGER NOT NULL, `uid` TEXT NOT NULL, `datetime` INTEGER NOT NULL, PRIMARY KEY(`logId`, `uid`))")
                database.execSQL("DROP TRIGGER insert_user_update_friend")
                database.execSQL("DROP TRIGGER update_user_update_friend")
                database.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS insert_user_update_friend AFTER INSERT ON user_info
                    BEGIN
                       INSERT OR REPLACE INTO friend_user(address, nickname, avatar, publicKey, flag, servers, groups, remark, searchKey) 
                       VALUES (new.address, new.nickname, new.avatar, new.publicKey, 0, new.servers, "[]", "", new.searchKey);
                    END;
                """)
                database.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS update_user_update_friend AFTER UPDATE OF address, nickname, avatar, server ON user_info
                    BEGIN
                       INSERT OR REPLACE INTO friend_user(address, nickname, avatar, publicKey, flag, servers, groups, remark, searchKey) 
                       VALUES (new.address, new.nickname, new.avatar, new.publicKey, 0, new.servers, "[]", "", new.searchKey);
                    END;
                """)
                database.execSQL("CREATE VIEW `view_message_focus` AS SELECT logId, count(1) as num FROM message_focus_user GROUP BY logId")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `group_user` ADD COLUMN `groupUserFlag` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `friend_user` ADD COLUMN `chainAddress` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `user_info` ADD COLUMN `chainAddress` TEXT NOT NULL DEFAULT ''")
                database.execSQL("DROP TRIGGER insert_user_update_friend")
                database.execSQL("DROP TRIGGER update_user_update_friend")
                database.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS insert_user_update_friend AFTER INSERT ON user_info
                    BEGIN
                       INSERT OR REPLACE INTO friend_user(address, nickname, avatar, publicKey, flag, servers, groups, chainAddress, remark, searchKey) 
                       VALUES (new.address, new.nickname, new.avatar, new.publicKey, 0, new.servers, "[]", new.chainAddress, "", new.searchKey);
                    END;
                """)
                database.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS update_user_update_friend AFTER UPDATE OF address, nickname, avatar, server, chainAddress ON user_info
                    BEGIN
                       INSERT OR REPLACE INTO friend_user(address, nickname, avatar, publicKey, flag, servers, groups, chainAddress, remark, searchKey) 
                       VALUES (new.address, new.nickname, new.avatar, new.publicKey, 0, new.servers, "[]", new.chainAddress, "", new.searchKey);
                    END;
                """)
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 阅后即焚待确认队列
                database.execSQL("CREATE TABLE IF NOT EXISTS `burn_pending_confirm` (`logId` INTEGER NOT NULL, `targetId` TEXT NOT NULL, `openedAt` INTEGER NOT NULL, PRIMARY KEY(`logId`))")
            }
        }

        /**
         * 11 -> 12
         *
         * 说明：此前在数据库版本号仍为 11 的情况下对 schema 做过改动（recent_session 新增 msg_burn 列、
         * 新增 burn_pending_confirm 表），导致旧 v11 库（缺上述结构）与新 v11 库的 identity 不一致，
         * 打开时 Room 会抛出 “Room cannot verify the data integrity”。这里统一升到 12 并做幂等补齐：
         * - recent_session 缺 msg_burn 列时补列（已存在则跳过，兼容已含该列的旧 v11 库）
         * - burn_pending_confirm 表不存在时创建
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 检查 recent_session 是否已有 msg_burn 列（不同时期的 v11 库结构不一致）
                var hasMsgBurn = false
                database.query("PRAGMA table_info(`recent_session`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == "msg_burn") {
                            hasMsgBurn = true
                            break
                        }
                    }
                }
                if (!hasMsgBurn) {
                    // 会话最近一条消息新增“是否阅后即焚”标记（默认 0 = 普通消息）
                    database.execSQL("ALTER TABLE `recent_session` ADD COLUMN `msg_burn` INTEGER NOT NULL DEFAULT 0")
                }
                // 阅后即焚待确认队列
                database.execSQL("CREATE TABLE IF NOT EXISTS `burn_pending_confirm` (`logId` INTEGER NOT NULL, `targetId` TEXT NOT NULL, `openedAt` INTEGER NOT NULL, PRIMARY KEY(`logId`))")
            }
        }
    }

    abstract fun userInfoDao(): UserInfoDao

    abstract fun companyUserDao(): CompanyUserDao

    abstract fun companyDao(): CompanyDao

    abstract fun friendUserDao(): FriendUserDao

    abstract fun recentSessionDao(): RecentSessionDao

    abstract fun messageDao(): MessageDao

    abstract fun searchHistoryDao(): SearchHistoryDao

    abstract fun ftsSearchDao(): FtsSearchDao

    abstract fun groupDao(): GroupDao

    abstract fun groupUserDao(): GroupUserDao

    abstract fun redPacketDao(): RedPacketDao

    abstract fun focusUserDao(): FocusUserDao

    abstract fun burnPendingDao(): BurnPendingDao
}

fun RoomDatabase.transaction(action: suspend () -> Unit) {
    GlobalScope.launch(Dispatchers.IO) {
        runInTransaction {
            GlobalScope.launch(Dispatchers.Main) {
                action.invoke()
            }
        }
    }
}
