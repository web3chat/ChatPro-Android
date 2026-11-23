package com.fzm.chat.core.data.model

import org.json.JSONObject

/**
 * 文本消息里的「链接卡片」。
 *
 * 消息仍然是普通 Text 消息（服务端无需改动），正文格式：
 *
 * ```
 * 胎教播放器
 * 8 USDT
 * https://fedmall.bityuan.com/#/product/xxx
 * [[LINK_CARD]]{"t":"胎教播放器","d":"8 USDT","u":"https://...","i":"https://...jpg","s":"零岁天才胎教早教"}
 * ```
 *
 * - `[[LINK_CARD]]` 之后的 JSON 是机器可读的卡片数据，客户端命中后渲染成卡片，不显示正文；
 * - 老版本客户端不认识该标记，会把正文原样显示出来（仍能看到标题、价格和链接）。
 *
 * JSON 字段：
 * - t：标题（必填）
 * - d：描述 / 副标题（如价格，可为空）
 * - u：跳转链接（必填，http/https）
 * - i：卡片缩略图（可为空）
 * - s：来源（可为空）
 */
data class LinkCard(
    val title: String,
    val description: String,
    val url: String,
    val image: String,
    val source: String
) {

    companion object {

        /** 卡片标记，必须与正文之间换行分隔 */
        const val MARKER = "[[LINK_CARD]]"

        /** 广场帖子卡片标记（与 chat-ui 的 SquareCardMessage 保持一致） */
        private const val SQUARE_CARD_MARKER = "[[SQUARE_CARD]]"

        private const val MAX_TITLE = 120
        private const val MAX_DESCRIPTION = 300
        private const val MAX_URL = 2048

        /**
         * 从文本消息中解析链接卡片；不是卡片消息或数据不合法时返回 null。
         */
        fun parse(content: String?): LinkCard? {
            if (content.isNullOrEmpty()) return null
            val index = content.lastIndexOf(MARKER)
            if (index < 0) return null
            val json = content.substring(index + MARKER.length).trim()
            if (json.isEmpty() || !json.startsWith("{")) return null
            return parseJson(json)
        }

        /**
         * 解析纯 JSON（不含标记），供 H5 / 服务端复用。
         */
        fun parseJson(json: String?): LinkCard? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val title = obj.optString("t").trim().take(MAX_TITLE)
                val url = obj.optString("u").trim()
                if (title.isEmpty() || url.isEmpty() || url.length > MAX_URL) return null
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
                LinkCard(
                    title = title,
                    description = obj.optString("d").trim().take(MAX_DESCRIPTION),
                    url = url,
                    image = obj.optString("i").trim(),
                    source = obj.optString("s").trim()
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 会话列表 / 通知栏的预览文案：
         * 命中链接卡片时返回「标题 描述」，命中广场帖子卡片时返回「[谈信广场] 作者：正文」，
         * 否则原样返回正文。
         */
        fun previewText(content: String?): String {
            val raw = content ?: return ""
            // 广场帖子卡片：[[SQUARE_CARD]]{...}
            val squareIndex = raw.lastIndexOf(SQUARE_CARD_MARKER)
            if (squareIndex >= 0) {
                val json = raw.substring(squareIndex + SQUARE_CARD_MARKER.length).trim()
                if (json.startsWith("{")) {
                    try {
                        val obj = JSONObject(json)
                        val text = obj.optString("c").trim()
                        val name = obj.optString("n").trim()
                        if (text.isNotEmpty()) {
                            return buildString {
                                append("[谈信广场]")
                                if (name.isNotEmpty()) append(name).append("：")
                                append(text)
                            }
                        }
                    } catch (e: Exception) {
                        // 解析失败按普通文本处理
                    }
                }
            }
            val card = parse(raw) ?: return raw
            return buildString {
                append(card.title)
                if (card.description.isNotEmpty()) append(" ").append(card.description)
            }
        }

        /**
         * 生成卡片消息正文：可读的三行 + 标记 + JSON。
         *
         * 可读部分让老客户端/通知栏也能看懂，JSON 部分给新客户端渲染卡片。
         */
        fun encode(
            title: String,
            description: String,
            url: String,
            image: String = "",
            source: String = ""
        ): String {
            val json = JSONObject().apply {
                put("t", title)
                put("d", description)
                put("u", url)
                put("i", image)
                put("s", source)
            }.toString()
            return buildString {
                append(title)
                if (description.isNotEmpty()) append("\n").append(description)
                append("\n").append(url)
                append("\n").append(MARKER).append(json)
            }
        }
    }
}
