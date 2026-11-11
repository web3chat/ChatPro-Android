package com.fzm.chat.square

import android.util.Log
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.router.core.CoreModule
import com.fzm.chat.router.core.CoreService
import com.fzm.chat.router.route
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.reflect.Type
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 谈信广场接口（C 端，前缀 /square/app）
 *
 * 接口文档见 doc/谈信广场.md
 *
 * - 基础 URL：`ChatConfig.SQUARE_SERVER`（生产地址见本机 chat-pro.properties，不入库）
 * - 接口前缀：`ChatConfig.SQUARE_PATH`（反向代理后缀，默认 `/square/app`，可在 chat-pro.properties 改）
 * - 认证：复用聊天客户端的 `FZM-SIGNATURE`（[CoreService.signAuth]），生产禁止 MOCK
 * - 业务结果以响应体 `result` 为准，HTTP 4xx/5xx 不做业务判断
 */
object SquareApi {

    private const val TAG = "SquareApi"

    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val coreService by route<CoreService>(CoreModule.SERVICE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val host: String
        get() = ChatConfig.SQUARE_SERVER.trimEnd('/')

    /** 接口前缀（反向代理暴露的后缀，默认 /square/app） */
    private val prefix: String
        get() = ChatConfig.SQUARE_PATH.trimEnd('/')

    /** 拼接接口路径（统一带上反代后缀） */
    private fun path(relative: String): String = prefix + relative

    // ==================== 广场流 ====================

    /**
     * 广场流（只返回 status=1 的可见帖）
     *
     * @param kind 0 仅个人帖 / 1 仅公众号帖；null 全部
     * @param authorAddr 只看某个作者的帖子（文档 6.1，服务端支持按作者筛帖）
     * @param feed latest 最新 / hot 近 7 天热门 / following 我关注的；null 默认最新。
     *            与 kind/authorAddr 为 AND 关系；`following` 未关注任何人时返回空列表
     * @param tags 官方帖标签筛选（多值为 OR：`?tag=公告&tag=消息`）；null/空不过滤
     */
    suspend fun feed(
        page: Int = 1,
        pageSize: Int = 20,
        kind: Int? = null,
        authorAddr: String? = null,
        feed: String? = null,
        tags: List<String>? = null
    ): SquareResp<SquarePage<SquarePost>> {
        val url = StringBuilder(path("/posts?page=$page&pageSize=$pageSize"))
        if (kind != null) url.append("&kind=$kind")
        if (!authorAddr.isNullOrEmpty()) {
            url.append("&authorAddr=").append(URLEncoder.encode(authorAddr, "UTF-8"))
        }
        if (!feed.isNullOrEmpty()) {
            url.append("&feed=").append(URLEncoder.encode(feed, "UTF-8"))
        }
        tags.orEmpty().filter { it.isNotBlank() }.forEach {
            url.append("&tag=").append(URLEncoder.encode(it, "UTF-8"))
        }
        return request(url.toString(), "GET", null, pageType())
    }

    /**
     * 广场开关状态（GET /square/app/status）
     *
     * 始终 `result=0`，用 [SquareStatus.readable] / [SquareStatus.postable] 判断，
     * **不要把关闭当接口失败**。
     */
    suspend fun status(): SquareResp<SquareStatus> {
        return request(path("/status"), "GET", null, SquareStatus::class.java)
    }

    /**
     * 帖子详情（非作者当天首次打开会 +1 阅读数）
     */
    suspend fun detail(postId: Long): SquareResp<SquarePost> {
        return request(path("/posts/$postId"), "GET", null, SquarePost::class.java)
    }

    /**
     * 发帖
     *
     * @param commentsEnabled 仅公众号帖可关评论，个人帖恒为开
     * @param contentFormat 正文格式：`plain` 纯文本 / `markdown` Markdown 原文（缺省时服务端按 plain 处理）
     */
    suspend fun publish(
        content: String,
        nickname: String?,
        mediaUrls: List<String>,
        commentsEnabled: Boolean = true,
        contentFormat: String? = null
    ): SquareResp<SquarePost> {
        val body = JsonObject().apply {
            addProperty("content", content)
            if (!nickname.isNullOrEmpty()) addProperty("nickname", nickname)
            add("mediaUrls", gson.toJsonTree(mediaUrls))
            addProperty("commentsEnabled", commentsEnabled)
            if (!contentFormat.isNullOrEmpty()) addProperty("contentFormat", contentFormat)
        }
        return request(path("/posts"), "POST", gson.toJson(body), SquarePost::class.java)
    }

    /**
     * 删除自己的帖子（软删）
     */
    suspend fun deletePost(postId: Long): SquareResp<Any> {
        return request(path("/posts/$postId"), "DELETE", null, anyType())
    }

    /**
     * 编辑自己的已发布帖（PUT /square/app/posts/:id）
     *
     * 请求体与发帖相同。个人帖 / 普通公众号帖仅发布后 20 分钟内可改，运营台官方帖不限时。
     * 结果：成功返回帖子对象（含 pinned / editUntil）；重新审核：待审 -20402、不合规 -20401；
     * 超时 -20415；他人帖 -20403；软删/草稿 -20404；限流 10 分钟 10 次 / 每天 40 次。
     */
    suspend fun updatePost(
        postId: Long,
        content: String,
        nickname: String?,
        mediaUrls: List<String>,
        commentsEnabled: Boolean = true,
        contentFormat: String? = null
    ): SquareResp<SquarePost> {
        val body = JsonObject().apply {
            addProperty("content", content)
            if (!nickname.isNullOrEmpty()) addProperty("nickname", nickname)
            add("mediaUrls", gson.toJsonTree(mediaUrls))
            addProperty("commentsEnabled", commentsEnabled)
            if (!contentFormat.isNullOrEmpty()) addProperty("contentFormat", contentFormat)
        }
        return request(path("/posts/$postId"), "PUT", gson.toJson(body), SquarePost::class.java)
    }

    /**
     * 举报帖子 / 公众号文章（同一地址对同一帖只能举报一次，重复 -20410；不会立刻藏帖）
     *
     * @param reason spam/porn/scam/abuse/other（[SquareReportReason]）
     * @param detail 补充说明，最长 500 字，可空
     */
    suspend fun reportPost(
        postId: Long,
        reason: String,
        detail: String? = null
    ): SquareResp<SquareReportResult> {
        val body = JsonObject().apply {
            addProperty("reason", reason)
            if (!detail.isNullOrEmpty()) addProperty("detail", detail)
        }
        return request(
            path("/posts/$postId/report"), "POST",
            gson.toJson(body), SquareReportResult::class.java
        )
    }

    /**
     * 点赞（幂等）
     */
    suspend fun likePost(postId: Long): SquareResp<SquareLikeResult> {
        return request(path("/posts/$postId/like"), "POST", null, SquareLikeResult::class.java)
    }

    /**
     * 取消点赞（幂等）
     */
    suspend fun unlikePost(postId: Long): SquareResp<SquareLikeResult> {
        return request(path("/posts/$postId/like"), "DELETE", null, SquareLikeResult::class.java)
    }

    // ==================== 评论 / 回复 ====================

    /**
     * 评论列表（仅可见评论）
     *
     * @param thread `true` 时按「楼」返回：只返回一级评论，每条带最多 2 条 `replies`（更早的在前），
     *               `total` 是楼数（翻页用），`commentCount` 是含回复的可见评论数（显示「N 条评论」用）。
     *               `false`（默认）时与旧接口一致：全部可见评论平铺，时间从早到晚。
     */
    suspend fun comments(
        postId: Long,
        page: Int = 1,
        pageSize: Int = 20,
        thread: Boolean = false
    ): SquareResp<SquarePage<SquareComment>> {
        val type = object : TypeToken<SquarePage<SquareComment>>() {}.type
        val threadParam = if (thread) "&thread=1" else ""
        return request(
            path("/posts/$postId/comments?page=$page&pageSize=$pageSize$threadParam"),
            "GET", null, type
        )
    }

    /**
     * 某楼的回复分页（GET /square/app/posts/:id/comments/:commentId/replies）
     *
     * @param commentId 必须是该帖的一级评论 id，否则 -20418
     */
    suspend fun commentReplies(
        postId: Long,
        commentId: Long,
        page: Int = 1,
        pageSize: Int = 20
    ): SquareResp<SquarePage<SquareComment>> {
        val type = object : TypeToken<SquarePage<SquareComment>>() {}.type
        return request(
            path("/posts/$postId/comments/$commentId/replies?page=$page&pageSize=$pageSize"),
            "GET", null, type
        )
    }

    /**
     * 发评论 / 回复（审核规则与发帖相同）
     *
     * @param replyToId 不传或 0：一级评论；大于 0：回复该条可见评论。
     *                  回复一条回复时服务端把 `parentId` 提升到该回复所属的一级评论。
     */
    suspend fun addComment(
        postId: Long,
        content: String,
        nickname: String?,
        replyToId: Long? = null
    ): SquareResp<SquareComment> {
        val body = JsonObject().apply {
            addProperty("content", content)
            if (!nickname.isNullOrEmpty()) addProperty("nickname", nickname)
            if (replyToId != null && replyToId > 0L) addProperty("replyToId", replyToId)
        }
        return request(
            path("/posts/$postId/comments"), "POST",
            gson.toJson(body), SquareComment::class.java
        )
    }

    /**
     * 删除自己的评论
     */
    suspend fun deleteComment(commentId: Long): SquareResp<Any> {
        return request(path("/comments/$commentId"), "DELETE", null, anyType())
    }

    // ==================== 评论收件箱 ====================

    /**
     * 收件箱（GET /square/app/replies）
     *
     * @param read null 全部历史（含已失效）/ 0 只看可展示未读 / 1 只看已读
     */
    suspend fun replies(
        page: Int = 1,
        pageSize: Int = 20,
        read: Int? = null
    ): SquareResp<SquareReplyPage> {
        val type = object : TypeToken<SquareReplyPage>() {}.type
        val readParam = if (read != null) "&read=$read" else ""
        return request(path("/replies?page=$page&pageSize=$pageSize$readParam"), "GET", null, type)
    }

    /**
     * 标已读（POST /square/app/replies/read）
     *
     * @param ids 指定的记录 id；[all] 为 true 时标记全部（忽略 [ids]）。只能改自己的记录。
     */
    suspend fun markRepliesRead(
        ids: List<Long>? = null,
        all: Boolean = false
    ): SquareResp<SquareUnreadResult> {
        val body = JsonObject().apply {
            if (all) {
                addProperty("all", true)
            } else {
                add("ids", gson.toJsonTree(ids ?: emptyList<Long>()))
            }
        }
        return request(
            path("/replies/read"), "POST",
            gson.toJson(body), SquareUnreadResult::class.java
        )
    }

    // ==================== 关注 / 粉丝 ====================

    /**
     * 关注用户 / 公众号（幂等；不能关注自己 → -20411）
     */
    suspend fun follow(targetAddr: String): SquareResp<SquareFollowResult> {
        val body = JsonObject().apply { addProperty("targetAddr", targetAddr) }
        return request(
            path("/follows"), "POST",
            gson.toJson(body), SquareFollowResult::class.java
        )
    }

    /**
     * 取消关注（幂等）
     */
    suspend fun unfollow(targetAddr: String): SquareResp<SquareFollowResult> {
        val addr = URLEncoder.encode(targetAddr, "UTF-8")
        return request(path("/follows/$addr"), "DELETE", null, SquareFollowResult::class.java)
    }

    /**
     * 关注列表
     *
     * @param address 为空：我关注的；传地址：该用户关注的（用户主页查看「TA 的关注」）
     */
    suspend fun follows(
        page: Int = 1,
        pageSize: Int = 50,
        address: String? = null
    ): SquareResp<SquarePage<SquareFollowUser>> {
        val type = object : TypeToken<SquarePage<SquareFollowUser>>() {}.type
        return request(
            path("/follows?page=$page&pageSize=$pageSize${addressParam(address)}"),
            "GET", null, type
        )
    }

    /**
     * 粉丝列表
     *
     * @param address 为空：我的粉丝；传地址：该用户的粉丝（用户主页查看「TA 的粉丝」）
     */
    suspend fun followers(
        page: Int = 1,
        pageSize: Int = 50,
        address: String? = null
    ): SquareResp<SquarePage<SquareFollowUser>> {
        val type = object : TypeToken<SquarePage<SquareFollowUser>>() {}.type
        return request(
            path("/followers?page=$page&pageSize=$pageSize${addressParam(address)}"),
            "GET", null, type
        )
    }

    /** 关注 / 粉丝列表的 address 过滤参数（空地址不拼接，保持「我自己的列表」语义） */
    private fun addressParam(address: String?): String {
        if (address.isNullOrBlank()) return ""
        return "&address=" + URLEncoder.encode(address, "UTF-8")
    }

    // ==================== 我的帖子流量 ====================

    /**
     * 我的帖子 + 流量汇总（含可见 / 隐藏 / 待审 / AI 拒绝，不含已删与草稿）
     */
    suspend fun mePosts(
        page: Int = 1,
        pageSize: Int = 20
    ): SquareResp<SquareMePosts> {
        return request(
            path("/me/posts?page=$page&pageSize=$pageSize"), "GET",
            null, SquareMePosts::class.java
        )
    }

    // ==================== 公众号 ====================

    /**
     * 开关评论（仅公众号作者）
     */
    suspend fun setCommentsEnabled(postId: Long, enabled: Boolean): SquareResp<Any> {
        val body = JsonObject().apply { addProperty("enabled", enabled) }
        return request(
            path("/posts/$postId/comments-enabled"), "PUT",
            gson.toJson(body), anyType()
        )
    }

    /**
     * 我的公众号状态
     */
    suspend fun officialMe(): SquareResp<SquareOfficialMe> {
        return request(path("/official/me"), "GET", null, SquareOfficialMe::class.java)
    }

    /**
     * 金标公众号列表（GET /square/app/officials）
     *
     * 默认只返回金标号（gold=1）与 status=1 正常号，按粉丝数降序、其次发帖数；
     * 广场完全关闭时与拉帖一样返回 -20413。
     * 点进某个号再用现有 `feed(authorAddr=)`，不要另开 `feed=gold`。
     */
    suspend fun officials(
        page: Int = 1,
        pageSize: Int = 20
    ): SquareResp<SquarePage<SquareOfficialCard>> {
        val type = object : TypeToken<SquarePage<SquareOfficialCard>>() {}.type
        return request(path("/officials?page=$page&pageSize=$pageSize"), "GET", null, type)
    }

    /**
     * 申请公众号（同地址同时只能有一条待审）
     */
    suspend fun applyOfficial(
        name: String,
        avatar: String?,
        intro: String?
    ): SquareResp<SquareOfficialApply> {
        val body = JsonObject().apply {
            addProperty("name", name)
            if (!avatar.isNullOrEmpty()) addProperty("avatar", avatar)
            if (!intro.isNullOrEmpty()) addProperty("intro", intro)
        }
        return request(
            path("/official/apply"), "POST",
            gson.toJson(body), SquareOfficialApply::class.java
        )
    }

    /**
     * 修改公众号资料（仅 state=approved 可用，封禁 -20405，未开号 -20404）
     *
     * @param avatar 传空字符串表示清空头像；传 null 表示不改动
     */
    suspend fun updateOfficial(name: String, avatar: String?): SquareResp<SquareOfficialProfile> {
        val body = JsonObject().apply {
            addProperty("name", name)
            if (avatar != null) addProperty("avatar", avatar)
        }
        return request(
            path("/official"), "PUT",
            gson.toJson(body), SquareOfficialProfile::class.java
        )
    }

    // ==================== 内部实现 ====================

    private fun pageType(): Type = object : TypeToken<SquarePage<SquarePost>>() {}.type

    private fun anyType(): Type = object : TypeToken<Any>() {}.type

    private suspend fun <T> request(
        path: String,
        method: String,
        body: String?,
        dataType: Type?
    ): SquareResp<T> = withContext(Dispatchers.IO) {
        val url = host + path
        try {
            val builder = Request.Builder()
                .url(url)
                .header("FZM-SIGNATURE", coreService?.signAuth() ?: "")
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                else -> builder.method(method, (body ?: "{}").toRequestBody(jsonType))
            }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string() ?: ""
                Log.i(TAG, "$method $url -> ${response.code}: ${text.take(500)}")
                parse(text, dataType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed", e)
            SquareResp(SquareCode.LOCAL_ERROR, e.message)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> parse(text: String, dataType: Type?): SquareResp<T> {
        return try {
            val json = gson.fromJson(text, JsonObject::class.java)
                ?: return SquareResp(SquareCode.LOCAL_ERROR, "响应解析失败")
            val result = json.get("result")?.takeIf { !it.isJsonNull }?.asInt
                ?: SquareCode.INTERNAL_ERROR
            val message = json.get("message")?.takeIf { !it.isJsonNull }?.asString
            val element = json.get("data")
            val data = if (dataType != null && element != null && !element.isJsonNull) {
                gson.fromJson(element, dataType) as T?
            } else {
                null
            }
            SquareResp(result, message, data)
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${text.take(200)}", e)
            SquareResp(SquareCode.LOCAL_ERROR, "响应解析失败")
        }
    }
}
