package com.fzm.chat.square

import com.fzm.chat.utils.AccountProfileCache

/**
 * 广场头像内存缓存
 *
 * 服务端帖子对象已返回 `authorAvatar`（公众号帖为当前公众号头像，个人帖为空串），
 * 缓存用于两种情况：
 * - 个人帖 / 公众号未设头像：按地址缓存从联系人（合约）查询到的链上头像，避免列表重复请求
 * - 公众号帖：用服务端返回的 `authorAvatar`，同时缓存到 officialAvatars（用户主页头部等场景需要按地址取）
 */
internal object SquareAvatarCache {

    private val avatars = mutableMapOf<String, String>()

    /** 已认证公众号头像（地址 -> 头像，本人号） */
    private val officialAvatars = mutableMapOf<String, String>()

    /** 已经发起过查询的地址（含查询中与查询失败） */
    private val requested = mutableSetOf<String>()

    /**
     * 取已缓存的（链上资料）头像地址，没有则返回 null（由调用方展示默认头像）
     */
    fun get(address: String?): String? {
        if (address.isNullOrEmpty()) return null
        avatars[address]?.takeIf { it.isNotBlank() }?.let { return it }
        // 本地账户缓存（AccountFragment 写入）兜底
        return AccountProfileCache.getAvatar(address).takeIf { it.isNotBlank() }
    }

    /**
     * 帖子展示头像
     *
     * 优先级：服务端返回的 authorAvatar（公众号帖）> 公众号头像缓存 > 链上资料头像
     */
    fun getForPost(post: SquarePost): String? {
        post.authorAvatar?.takeIf { it.isNotBlank() }?.let { return it }
        return getForAddress(post.authorAddr, post.isOfficial)
    }

    /**
     * 按地址取展示头像
     *
     * @param official 该地址是否为公众号（官方帖/公众号主页）
     */
    fun getForAddress(address: String?, official: Boolean): String? {
        if (address.isNullOrEmpty()) return null
        if (official) {
            officialAvatars[address]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return get(address)
    }

    /**
     * 缓存本人公众号头像（officialMe / 改资料返回时调用）
     *
     * @param fallbackAddress 响应未携带 address 时用当前登录地址兜底
     */
    fun cacheOfficial(me: SquareOfficialMe?, fallbackAddress: String? = null) {
        val address = me?.address?.takeIf { it.isNotBlank() }
            ?: fallbackAddress?.takeIf { it.isNotBlank() }
            ?: return
        val official = me?.official ?: return
        putOfficial(address, official.avatar.orEmpty())
    }

    /**
     * @param avatar 空字符串表示清除（未设置头像时回落到链上资料头像）
     */
    fun putOfficial(address: String, avatar: String) {
        if (address.isBlank()) return
        if (avatar.isBlank()) {
            officialAvatars.remove(address)
        } else {
            officialAvatars[address] = avatar
        }
    }

    /**
     * 同步服务端帖子对象里的公众号头像（广场流/详情/用户主页拉到帖子后调用）
     *
     * - 非空：写入缓存（别人的公众号帖头像也能拿到，用户主页头部可以直接用）
     * - 空串：服务端说该公众号当前没头像 → 清掉缓存，回退链上头像
     * - null（字段缺失，旧服务端）：忽略，不动缓存
     *
     * @return 缓存是否有变化（有变化时调用方可刷新按地址取头像的 UI）
     */
    fun absorbPosts(posts: List<SquarePost>?): Boolean {
        var changed = false
        posts?.forEach { post ->
            if (!post.isOfficial) return@forEach
            val address = post.authorAddr?.takeIf { it.isNotBlank() } ?: return@forEach
            val avatar = post.authorAvatar ?: return@forEach
            if (avatar.isBlank()) {
                if (officialAvatars.remove(address) != null) changed = true
            } else if (officialAvatars[address] != avatar) {
                officialAvatars[address] = avatar
                changed = true
            }
        }
        return changed
    }

    /**
     * 是否已经查询过（避免同一地址反复请求合约）
     */
    fun contains(address: String): Boolean {
        return avatars.containsKey(address) || requested.contains(address)
    }

    fun markRequested(address: String) {
        requested.add(address)
    }

    fun put(address: String, avatar: String) {
        avatars[address] = avatar
    }
}
