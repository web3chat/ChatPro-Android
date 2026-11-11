package com.fzm.chat.square

/**
 * 广场开关内存状态（GET /square/app/status、official/me 的 square 字段）
 *
 * 文档《App增量-广场开关与审核强度》：运营可能关掉广场或限制发帖，
 * 进入广场前先问一次状态，用 canRead / canPost 决定是否展示列表和发帖按钮：
 * - `canRead=false`：不要请求帖子列表，全屏展示 `message`
 * - `canPost=false`：正常刷广场，隐藏发帖 / 编辑 / 关评论
 *
 * 页面（广场 / 详情 / 我的帖）共读这里的缓存；未加载时默认放行，
 * 服务端仍会以 -20413 / -20414 兑底（拉帖/发帖时按返回结果处理）。
 */
object SquareGate {

    /** 能否看帖（false = 广场已完全关闭，展示 [message]） */
    @Volatile
    var canRead: Boolean = true
        private set

    /** 能否发帖 / 编辑 / 关评论 */
    @Volatile
    var canPost: Boolean = true
        private set

    /** 关闭说明（运营文案多变，原样展示） */
    @Volatile
    var message: String = ""
        private set

    fun update(status: SquareStatus?) {
        if (status == null) return
        canRead = status.readable
        canPost = status.postable
        status.notice.takeIf { it.isNotBlank() }?.let { message = it }
    }
}
