package com.fzm.chat.square

import org.json.JSONObject

/**
 * 广场帖子卡片消息：正文为
 * ```
 * 【谈信广场】作者名
 * 帖子正文（纯文本）
 * [[SQUARE_CARD]]{"id":715,"c":"正文","n":"作者","av":"头像","im":"配图","t":"时间","v":1}
 * ```
 *
 * - 标记前面的可读文本给老客户端/通知栏兜底
 * - `[[SQUARE_CARD]]` 后面的 JSON 给新客户端渲染成帖子卡片
 * - 与 iOS 端协议保持一致（av/c/id/im/n/t/v）
 */
object SquareCardMessage {

    const val MARKER = "[[SQUARE_CARD]]"

    private const val VERSION = 1

    data class Card(
        val postId: Long,
        val content: String,
        val name: String,
        val avatar: String,
        val image: String,
        val time: String
    )

    /**
     * 生成聊天消息正文
     */
    fun encode(post: SquarePost): String {
        val content = if (post.isMarkdown) {
            SquareMarkdown.toPlainText(post.content)
        } else {
            post.content.orEmpty()
        }
        val json = JSONObject().apply {
            put("id", post.postId)
            put("c", content)
            put("n", post.displayName)
            put("av", post.authorAvatar.orEmpty())
            put("im", post.images.firstOrNull().orEmpty())
            put("t", post.createdAt.orEmpty())
            put("v", VERSION)
        }.toString()
        return buildString {
            append("【谈信广场】").append(post.displayName)
            // 与 iOS 端一致：可读部分带一行时间
            val timeText = post.createdAt.orEmpty().take(16).replace('T', ' ')
            if (timeText.isNotBlank()) {
                append("\n").append(timeText)
            }
            if (content.isNotBlank()) {
                append("\n").append(content)
            }
            append("\n").append(MARKER).append(json)
        }
    }

    /**
     * 解析卡片；不是帖子卡片返回 null
     */
    fun parse(raw: String?): Card? {
        if (raw.isNullOrEmpty()) return null
        val index = raw.lastIndexOf(MARKER)
        if (index < 0) return null
        val json = raw.substring(index + MARKER.length).trim()
        if (!json.startsWith("{")) return null
        return try {
            val obj = JSONObject(json)
            val postId = obj.optLong("id", 0L)
            if (postId <= 0L) return null
            Card(
                postId = postId,
                content = obj.optString("c").trim(),
                name = obj.optString("n").trim(),
                avatar = obj.optString("av").trim(),
                image = obj.optString("im").trim(),
                time = obj.optString("t").trim()
            )
        } catch (e: Exception) {
            null
        }
    }
}
