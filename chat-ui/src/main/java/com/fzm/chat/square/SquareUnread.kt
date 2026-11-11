package com.fzm.chat.square

import android.content.Context
import com.fzm.chat.utils.PreferencesUtils

/**
 * 广场「未读新帖」本地基准
 *
 * 广场服务端没有未读 / 通知接口（doc/广场2.md 只有 feed/detail/follows 等），
 * 因此未读数由客户端统计：
 * 1. 记录上次进入广场时最新流里看到的最大帖子 id（[lastSeenId]）；
 * 2. 检查时拉最新流第 1 页，统计 id 大于基准的帖子数（[countNew]）；
 * 3. 进入广场 tab 即记为已读（[markSeen]）。
 *
 * 置顶帖（pinned）是运营挑出来的旧帖，id 小于新帖，天然不会被计入。
 */
object SquareUnread {

    /** 上次进入广场时最新流里最大的帖子 id（0 = 还没有基准） */
    private const val KEY_LAST_SEEN_ID = "square_last_seen_post_id"

    fun lastSeenId(context: Context): Long =
        PreferencesUtils.getLong(context, KEY_LAST_SEEN_ID, 0L)

    /**
     * 静默建立基准（仅在还没有基准时写入）
     *
     * 首次安装 / 升级后没有历史基准，如果直接统计会一次性弹出一屏的「未读」，
     * 所以第一次检查时把当前最新帖子当作已读。
     */
    fun ensureSeen(context: Context, newestId: Long) {
        if (newestId <= 0L) return
        if (lastSeenId(context) <= 0L) {
            PreferencesUtils.putLong(context, KEY_LAST_SEEN_ID, newestId)
        }
    }

    /** 记为已读：只增不减，避免列表刷新回滚时基准变小又把旧帖算成未读 */
    fun markSeen(context: Context, newestId: Long) {
        if (newestId <= 0L) return
        if (newestId > lastSeenId(context)) {
            PreferencesUtils.putLong(context, KEY_LAST_SEEN_ID, newestId)
        }
    }

    /** 统计比基准新的帖子数（基准未建立时返回 0） */
    fun countNew(context: Context, posts: List<SquarePost>): Int {
        val last = lastSeenId(context)
        if (last <= 0L) return 0
        return posts.count { it.postId > last }
    }
}
