package com.fzm.chat.square

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 关注列表内存缓存
 *
 * 帖子对象不返回 `followed` 字段（增量文档：请用关注列表自行维护），
 * 但用户主页的「关注 / 已关注」按钮需要知道「我有没有关注这个地址」，
 * 因此把关注列表拉到内存里维护：
 *
 * - [ensureLoaded]：分页拉全量关注列表（最多 [MAX_PAGE] 页），成功才算加载完成
 * - [isFollowed] / [markFollowed]：查询与本地更新（关注 / 取关成功后立即更新，无需重新拉列表）
 */
object SquareFollowCache {

    private const val PAGE_SIZE = 100

    private const val MAX_PAGE = 20

    private val followed = mutableSetOf<String>()

    private val mutex = Mutex()

    /** 是否已完整加载过关注列表（加载失败仍为 false，下次会重试） */
    @Volatile
    var loaded = false
        private set

    fun isFollowed(address: String?): Boolean {
        val key = address?.takeIf { it.isNotBlank() }?.lowercase() ?: return false
        return followed.contains(key)
    }

    fun markFollowed(address: String?, followed: Boolean) {
        val key = address?.takeIf { it.isNotBlank() }?.lowercase() ?: return
        if (followed) this.followed.add(key) else this.followed.remove(key)
    }

    /**
     * 拉取全量关注列表（已加载过直接返回 true）
     */
    suspend fun ensureLoaded(): Boolean {
        if (loaded) return true
        return mutex.withLock {
            if (loaded) return@withLock true
            val set = mutableSetOf<String>()
            var complete = false
            var page = 1
            while (page <= MAX_PAGE) {
                val resp = SquareApi.follows(page, PAGE_SIZE)
                if (!resp.isSuccess) break
                val items = resp.data?.items ?: emptyList()
                items.forEach { item ->
                    item.address?.takeIf { it.isNotBlank() }?.let { set.add(it.lowercase()) }
                }
                val total = resp.data?.totalNum ?: items.size
                if (items.isEmpty() || set.size >= total) {
                    complete = true
                    break
                }
                page++
            }
            if (complete) {
                followed.clear()
                followed.addAll(set)
                loaded = true
            }
            loaded
        }
    }
}
