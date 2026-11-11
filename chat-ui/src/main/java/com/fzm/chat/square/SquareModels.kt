package com.fzm.chat.square

import android.content.Context
import com.fzm.chat.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 谈信广场接口返回码（详见 doc/谈信广场.md）
 */
object SquareCode {

    const val SUCCESS = 0

    /** 无效签名（含 MOCK） */
    const val INVALID_SIGN = -1010

    /** 签名过期 */
    const val SIGN_EXPIRED = -1011

    /** 参数错误 */
    const val PARAM_ERROR = -1012

    /** 内部错误 */
    const val INTERNAL_ERROR = -1000

    /** 内容不合规 */
    const val CONTENT_REJECTED = -20401

    /** 已提交，等待审核 */
    const val PENDING_REVIEW = -20402

    /** 无权操作 */
    const val NO_PERMISSION = -20403

    /** 资源不存在 */
    const val NOT_FOUND = -20404

    /** 公众号已封禁 */
    const val OFFICIAL_BANNED = -20405

    /** 非公众号 */
    const val NOT_OFFICIAL = -20406

    /** 评论已关闭 */
    const val COMMENT_CLOSED = -20407

    /** 已有待审申请 */
    const val APPLY_PENDING = -20408

    /** 已举报过该内容 */
    const val REPORT_DUPLICATE = -20410

    /** 不能关注自己 */
    const val FOLLOW_SELF = -20411

    /** 广场已完全关闭（拉帖/详情/发帖/评论/点赞/举报） */
    const val SQUARE_CLOSED = -20413

    /** 当前账号不能发帖（半关闭） */
    const val CANNOT_POST = -20414

    /** 超过可编辑时间 */
    const val EDIT_EXPIRED = -20415

    /** 账号被封禁 */
    const val USER_BANNED = -20409

    /** 操作过于频繁 */
    const val RATE_LIMITED = -20429

    /** 不能回复该评论（目标不可见、已删，或不属于这条帖） */
    const val REPLY_TARGET_INVALID = -20418

    /** 本地错误（网络异常等，非服务端返回） */
    const val LOCAL_ERROR = -10001
}

/**
 * 广场统一响应：`result === 0` 成功，其余按码提示
 *
 * 注意：HTTP 状态码业务失败时仍多为 200，以 result 为准。
 */
class SquareResp<T>(
    val result: Int = SquareCode.INTERNAL_ERROR,
    val message: String? = null,
    val data: T? = null
) {

    val isSuccess: Boolean
        get() = result == SquareCode.SUCCESS

    /** 已提交待审核：内容已入库，按「成功待审」处理 */
    val isPendingReview: Boolean
        get() = result == SquareCode.PENDING_REVIEW

    val isRejected: Boolean
        get() = result == SquareCode.CONTENT_REJECTED

    val isRateLimited: Boolean
        get() = result == SquareCode.RATE_LIMITED

    fun hasData(): Boolean = isSuccess && data != null
}

/**
 * 广场流类型（列表 `feed` 参数）
 */
object SquareFeed {

    /** 最新（默认） */
    const val LATEST = "latest"

    /** 近 7 天热门（赞/评加权排序） */
    const val HOT = "hot"

    /** 我关注的人 / 公众号 */
    const val FOLLOWING = "following"
}

/**
 * 举报原因（POST /square/app/posts/:id/report 的 reason）
 */
object SquareReportReason {

    /** 垃圾内容 */
    const val SPAM = "spam"

    /** 色情低俗 */
    const val PORN = "porn"

    /** 诈骗信息 */
    const val SCAM = "scam"

    /** 辱骂攻击 */
    const val ABUSE = "abuse"

    /** 其他 */
    const val OTHER = "other"
}

/**
 * 帖子对象
 */
data class SquarePost(
    val id: Long? = null,
    val authorAddr: String? = null,
    val authorName: String? = null,
    /** 0 个人 / 1 公众号 */
    val authorKind: Int? = null,
    /** 作者头像（服务端若返回则优先使用，文档暂未提供该字段） */
    val authorAvatar: String? = null,
    /** 公众号金标（运营台授予；个人帖恒为 false） */
    val authorGold: Boolean? = null,
    val content: String? = null,
    /** plain 纯文本 / markdown 官方帖 Markdown 原文（字段始终存在） */
    val contentFormat: String? = null,
    val mediaUrls: List<String>? = null,
    val commentsEnabled: Boolean? = null,
    /** 1 可见 2 隐藏 3 已删除 4 审核拒绝 5 待人工审核 */
    val status: Int? = null,
    val commentCount: Int? = null,
    val likeCount: Int? = null,
    val viewCount: Int? = null,
    val liked: Boolean? = null,
    val createdAt: String? = null,
    /** 官方帖标签：公告 / 消息 / 活动；个人帖与普通公众号帖为空 */
    val tag: String? = null,
    /** 运营置顶（广场流第 1 页最前，最多 3 条） */
    val pinned: Boolean? = null,
    val pinnedAt: String? = null,
    /** 可编辑截止时间（RFC3339；运营台官方帖为 null = 不限时） */
    val editUntil: String? = null
) {

    val postId: Long
        get() = id ?: 0L

    val isOfficial: Boolean
        get() = authorKind == 1

    val displayName: String
        get() = authorName?.takeIf { it.isNotBlank() } ?: (authorAddr ?: "")

    val images: List<String>
        get() = mediaUrls ?: emptyList()

    val canComment: Boolean
        get() = commentsEnabled != false

    val isLiked: Boolean
        get() = liked == true

    val likeNum: Int
        get() = likeCount ?: 0

    val commentNum: Int
        get() = commentCount ?: 0

    val viewNum: Int
        get() = viewCount ?: 0

    /** 审核拒绝（仅作者自己详情可见） */
    val isRejected: Boolean
        get() = status == 4

    /** 待人工审核（仅作者自己详情可见） */
    val isAuditing: Boolean
        get() = status == 5

    /** 正文是否为 Markdown（仅平台官方帖为 markdown，其余为 plain） */
    val isMarkdown: Boolean
        get() = contentFormat?.equals(CONTENT_MARKDOWN, ignoreCase = true) == true

    /** 官方帖标签（无标签为空串） */
    val postTag: String
        get() = tag.orEmpty()

    /** 运营置顶帖 */
    val isPinned: Boolean
        get() = pinned == true

    /** 金标公众号帖（名称旁画金标） */
    val isGold: Boolean
        get() = isOfficial && authorGold == true

    /** 是否为运营台官方账号（其帖不限编辑时限） */
    val isPlatformOfficial: Boolean
        get() = isPlatformOfficialAddress(authorAddr)

    /**
     * 是否仍在可编辑时限内（文档 §3，按发布时间计）
     *
     * - 个人帖 / 普通公众号帖：`createdAt + 20 分钟`（含整点，即 now <= editUntil）
     * - 运营台官方帖：不限时（editUntil == null）
     *
     * ⚠️ 窗口以服务端时间为准，这里仅用于是否展示编辑入口；超时提交由服务端返回 -20415。
     */
    fun editable(now: Date = Date()): Boolean {
        val until = editUntil
        if (until.isNullOrEmpty()) return isPlatformOfficial
        val deadline = SquareTime.parse(until) ?: return false
        return !now.after(deadline)
    }

    companion object {
        const val CONTENT_PLAIN = "plain"
        const val CONTENT_MARKDOWN = "markdown"

        /** 运营台官方账号地址（官方帖作者） */
        const val PLATFORM_OFFICIAL = "square-official"
    }
}

/**
 * 是否为运营台官方账号地址（`authorAddr = square-official`）
 *
 * 官方帖可带分类标签（公告/消息/活动）、不限编辑时限，身份标识为金色「官方号」。
 */
internal fun isPlatformOfficialAddress(address: String?): Boolean =
    address?.equals(SquarePost.PLATFORM_OFFICIAL, ignoreCase = true) == true

/**
 * 评论对象
 *
 * 评论仍是两级：`parentId` 为空/0 是一级评论（楼），大于 0 的回复都挂在同一楼下面；
 * `replyToId` 指向被点的那条评论（回复回复时仍是被点的那条，楼由服务端提升）。
 */
data class SquareComment(
    val id: Long? = null,
    val postId: Long? = null,
    val authorAddr: String? = null,
    val authorName: String? = null,
    val content: String? = null,
    val status: Int? = null,
    val createdAt: String? = null,
    /** 评论作者是否公众号（增量字段，缺省按个人处理） */
    val isOfficial: Boolean? = null,
    /** 所属一级评论 id；空/0 表示本身就是一级评论 */
    val parentId: Long? = null,
    /** 被回复的评论 id；空/0 表示直接评论帖子 */
    val replyToId: Long? = null,
    /** 被回复者地址 */
    val replyToAddr: String? = null,
    /** 被回复者展示名 */
    val replyToName: String? = null,
    /** 该楼下的可见回复数 */
    val replyCount: Int? = null,
    /** 一级评论已不可见但有可见回复时的占位（正文与作者名为空） */
    val deleted: Boolean? = null,
    /** `thread=1` 时随楼返回的前 2 条回复（更早的在前） */
    val replies: List<SquareComment>? = null
) {

    val commentId: Long
        get() = id ?: 0L

    val displayName: String
        get() = authorName?.takeIf { it.isNotBlank() } ?: (authorAddr ?: "")

    /** 所在楼的 id（自己是一级评论时就是自己的 id） */
    val floorId: Long
        get() = parentId?.takeIf { it > 0L } ?: commentId

    /** 是否为二级回复 */
    val isReply: Boolean
        get() = (parentId ?: 0L) > 0L

    /** 被回复者展示名（回复评论时才有） */
    val replyTargetName: String
        get() = replyToName?.takeIf { it.isNotBlank() } ?: (replyToAddr ?: "")

    /** 该楼的可见回复数 */
    val replyNum: Int
        get() = replyCount ?: replies?.size ?: 0

    /** 一级评论已被删除（仅楼占位） */
    val isDeleted: Boolean
        get() = deleted == true

    val official: Boolean
        get() = isOfficial == true
}

/**
 * 分页数据
 *
 * 评论列表额外带 `commentCount`：含回复的可见评论数（用于「N 条评论」），
 * `thread=1` 时 `total` 是楼数、`commentCount` 才是评论总数。
 */
class SquarePage<T>(
    val list: List<T>? = null,
    val total: Int? = null,
    val page: Int? = null,
    val pageSize: Int? = null,
    val commentCount: Int? = null
) {

    val items: List<T>
        get() = list ?: emptyList()

    val totalNum: Int
        get() = total ?: items.size
}

/**
 * 点赞结果
 */
class SquareLikeResult(
    val likeCount: Int? = null,
    val liked: Boolean? = null
) {

    val likeNum: Int
        get() = likeCount ?: 0

    val isLiked: Boolean
        get() = liked == true
}

/**
 * 关注 / 取关结果
 */
class SquareFollowResult(
    val targetAddr: String? = null,
    val followed: Boolean? = null
) {

    val isFollowed: Boolean
        get() = followed == true
}

/**
 * 关注 / 粉丝列表项（用户与公众号都按钱包地址）
 */
class SquareFollowUser(
    val address: String? = null,
    /** 公众号为号名；个人取最近发帖昵称，没有则为地址 */
    val name: String? = null,
    /** 公众号头像；个人为空 */
    val avatar: String? = null,
    /** 0 个人 / 1 公众号 */
    val targetKind: Int? = null,
    val followedAt: String? = null
) {

    val addr: String
        get() = address.orEmpty()

    val isOfficial: Boolean
        get() = targetKind == 1

    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: addr
}

/**
 * 我的帖子 + 流量汇总（GET /square/app/me/posts）
 *
 * 只返回当前验签地址的帖子：不含已删、不含草稿；含可见 / 隐藏 / 待审 / AI 拒绝。
 */
class SquareMePosts(
    val list: List<SquarePost>? = null,
    /** 列表总数（同口径） */
    val total: Int? = null,
    val totalPosts: Int? = null,
    val totalViews: Int? = null,
    val totalLikes: Int? = null,
    val totalComments: Int? = null
) {

    val items: List<SquarePost>
        get() = list ?: emptyList()

    val totalNum: Int
        get() = total ?: items.size

    val postNum: Int
        get() = totalPosts ?: totalNum

    val viewNum: Int
        get() = totalViews ?: 0

    val likeNum: Int
        get() = totalLikes ?: 0

    val commentNum: Int
        get() = totalComments ?: 0
}

/**
 * 举报结果
 */
class SquareReportResult(
    val id: Long? = null,
    val targetId: Long? = null,
    val reason: String? = null,
    /** 0 待处理 */
    val status: Int? = null
)

/**
 * 已认证公众号信息
 */
class SquareOfficial(
    val name: String? = null,
    val avatar: String? = null,
    val intro: String? = null,
    /** 1 正常 / 2 封禁 */
    val status: Int? = null,
    /** 金标（运营台超级管理员审核授予，App 不能申请） */
    val gold: Boolean? = null
) {

    val banned: Boolean
        get() = status == 2

    val isGold: Boolean
        get() = gold == true
}

/**
 * 最近一次公众号申请
 */
class SquareOfficialApply(
    val id: Long? = null,
    val status: String? = null,
    val name: String? = null,
    val remark: String? = null
)

/**
 * 修改公众号资料后的返回（PUT /square/app/official）
 */
class SquareOfficialProfile(
    val address: String? = null,
    val name: String? = null,
    val avatar: String? = null
)

/**
 * 广场开关状态（GET /square/app/status，或 official/me 的 square 字段）
 *
 * 始终 `result=0`，用 [readable] / [postable] 判断；不要把关闭当接口失败。
 */
class SquareStatus(
    val canRead: Boolean? = null,
    val canPost: Boolean? = null,
    val message: String? = null
) {

    val readable: Boolean
        get() = canRead != false

    val postable: Boolean
        get() = canPost != false

    val notice: String
        get() = message.orEmpty()
}

/**
 * 金标公众号卡片（GET /square/app/officials）
 */
data class SquareOfficialCard(
    val address: String? = null,
    val name: String? = null,
    val avatar: String? = null,
    val intro: String? = null,
    val gold: Boolean? = null,
    val postCount: Int? = null,
    val followerCount: Int? = null,
    /** 当前验签地址是否已关注 */
    val followed: Boolean? = null
) {

    val addr: String
        get() = address.orEmpty()

    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: addr

    /** 该接口默认只返回金标号，字段缺失时按金标处理 */
    val isGold: Boolean
        get() = gold != false

    val followers: Int
        get() = followerCount ?: 0

    val posts: Int
        get() = postCount ?: 0

    val isFollowed: Boolean
        get() = followed == true
}

/**
 * 我的公众号状态
 */
class SquareOfficialMe(
    /** none / pending / approved / rejected / banned */
    val state: String? = null,
    val address: String? = null,
    val official: SquareOfficial? = null,
    val apply: SquareOfficialApply? = null,
    /** 广场开关（与 GET /status 同结构，可复用） */
    val square: SquareStatus? = null
) {

    companion object {
        const val NONE = "none"
        const val PENDING = "pending"
        const val APPROVED = "approved"
        const val REJECTED = "rejected"
        const val BANNED = "banned"
    }

    val currentState: String
        get() = state ?: NONE

    val isApproved: Boolean
        get() = currentState == APPROVED

    val isBanned: Boolean
        get() = currentState == BANNED
}

/**
 * 广场收件箱条目（GET /square/app/replies）
 *
 * 广场内通知：帖子作者（一级评论）或被回复的人（回复）。不接 IM。
 * `replyToId = 0` 表示有人评论了你的帖子，大于 0 表示有人回复了你的评论。
 */
data class SquareReplyItem(
    val id: Long? = null,
    val postId: Long? = null,
    /** 新增的那条评论 id */
    val commentId: Long? = null,
    /** 该评论所属的一级评论 id */
    val parentId: Long? = null,
    /** 0 = 有人评论了你的帖；大于 0 = 有人回复了你的评论 */
    val replyToId: Long? = null,
    /** 写入时的帖子标题（标题为空时用简介或正文前 64 字） */
    val postTitle: String? = null,
    /** 帖子当前是否可见 */
    val postAvailable: Boolean? = null,
    /** 帖子可见 + 评论可见 + 正文非空，才能点进去 */
    val available: Boolean? = null,
    /** 评论正文；评论不可见时为空 */
    val content: String? = null,
    val authorAddr: String? = null,
    val authorName: String? = null,
    val createdAt: String? = null,
    val read: Boolean? = null
) {

    val itemId: Long
        get() = id ?: 0L

    val postIdValue: Long
        get() = postId ?: 0L

    val title: String
        get() = postTitle.orEmpty()

    val isRead: Boolean
        get() = read == true

    /** 可点进去（帖子与评论都还在） */
    val isAvailable: Boolean
        get() = available == true

    /** 有人评论了我的帖子（而非回复我的评论） */
    val isPostComment: Boolean
        get() = (replyToId ?: 0L) <= 0L

    val displayName: String
        get() = authorName?.takeIf { it.isNotBlank() } ?: (authorAddr ?: "")
}

/**
 * 收件箱分页（GET /square/app/replies）
 *
 * `unreadCount` 与 `read=0` 同一条件：未读且可展示（评论被隐藏或帖子已删不计未读）。
 */
class SquareReplyPage(
    val list: List<SquareReplyItem>? = null,
    val total: Int? = null,
    val page: Int? = null,
    val pageSize: Int? = null,
    val unreadCount: Int? = null
) {

    val items: List<SquareReplyItem>
        get() = list ?: emptyList()

    val totalNum: Int
        get() = total ?: items.size

    val unread: Int
        get() = unreadCount ?: 0
}

/**
 * 标已读后的返回（POST /square/app/replies/read）：`{ "unreadCount": 0 }`
 */
class SquareUnreadResult(val unreadCount: Int? = null) {

    val unread: Int
        get() = unreadCount ?: 0
}

/**
 * 时间展示工具：服务端时间为 RFC3339（如 2026-09-15T11:00:00+08:00）
 */
object SquareTime {

    private val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss"
    )

    /**
     * 解析 RFC3339 时间（如 2026-09-15T11:00:00+08:00），只取前 19 位日期时间部分
     */
    fun parse(time: String?): Date? {
        if (time.isNullOrEmpty() || time.length < 19) return null
        val value = time.substring(0, 19)
        for (pattern in formats) {
            try {
                return SimpleDateFormat(pattern, Locale.getDefault()).parse(value)
            } catch (e: Exception) {
                // try next
            }
        }
        return null
    }

    /**
     * 列表展示：刚刚 / x 分钟前 / x 小时前 / 昨天 / MM-dd / yyyy-MM-dd
     *
     * 文案取字符串资源，必须传 UI 的 Context：`BizActivity.attachBaseContext` 已按
     * 应用内选择的语言包装（`Arch.context` 仍是系统语言，不能用）。
     */
    fun display(context: Context, time: String?): String {
        val date = parse(time) ?: return time ?: ""
        val now = System.currentTimeMillis()
        val diff = now - date.time
        val minute = 60 * 1000L
        val hour = 60 * minute
        val day = 24 * hour
        val resources = context.resources
        return when {
            diff < minute -> resources.getString(R.string.square_time_just_now)
            diff < hour -> resources.getString(R.string.square_time_minutes, diff / minute)
            diff < day -> resources.getString(R.string.square_time_hours, diff / hour)
            isSameDay(date, Date(now - day)) -> resources.getString(R.string.square_time_yesterday)
            isSameYear(date) -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(date)
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
        }
    }

    private fun isSameDay(a: Date, b: Date): Boolean {
        val ca = Calendar.getInstance().apply { time = a }
        val cb = Calendar.getInstance().apply { time = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
                ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private fun isSameYear(date: Date): Boolean {
        return Calendar.getInstance().get(Calendar.YEAR) ==
                Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
    }
}
