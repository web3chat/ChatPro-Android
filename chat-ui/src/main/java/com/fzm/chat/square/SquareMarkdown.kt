package com.fzm.chat.square

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/**
 * 官方帖 Markdown 渲染（App 增量：平台官方帖正文支持 Markdown）
 *
 * 服务端在帖子对象里返回 `contentFormat`：官方帖为 `markdown`（正文是 Markdown 原文，不是 HTML），
 * 其余帖子为 `plain`（纯文本）。这里做**原生渲染**，不经过 WebView，因此不存在脚本注入问题。
 *
 * 支持文档标注的常用语法：
 * - 标题：`#` / `##` / `###`
 * - 粗体 `**粗体**`、斜体 `*斜体*`、删除线 `~~删除线~~`、行内代码 `` `代码` ``
 * - 无序列表 `-` / `*`、有序列表 `1.`
 * - 引用 `>`、围栏代码块 ``` ```
 * - 链接 `[文字](https://链接)`
 *
 * 语法不完整（没闭合）时按原样展示，不会吞掉用户内容。
 */
object SquareMarkdown {

    private const val LINK_COLOR = 0xFF1B8FE3.toInt()

    private const val CODE_BG_COLOR = 0xFFF2F3F5.toInt()

    private const val QUOTE_COLOR = 0xFF8A97A5.toInt()

    private const val CODE_TYPEFACE = "monospace"

    private val headingPattern = Regex("^(#{1,6})\\s+(.*)$")

    private val quotePattern = Regex("^>\\s?(.*)$")

    private val unorderedPattern = Regex("^\\s*[-*+]\\s+(.*)$")

    private val orderedPattern = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")

    private val hrPattern = Regex("^\\s*([-*_])\\1{2,}\\s*$")

    /**
     * 渲染为可展示的富文本（标题/粗斜体/列表/引用/代码/链接）
     *
     * @return 富文本；内容为空时返回空串
     */
    fun render(source: String?): CharSequence {
        val text = source?.takeIf { it.isNotBlank() }?.replace("\r\n", "\n") ?: return ""
        val out = SpannableStringBuilder()
        var inCodeBlock = false
        val codeBlock = StringBuilder()

        fun flushCodeBlock() {
            if (codeBlock.isEmpty()) return
            val start = out.length
            out.append(codeBlock.toString().trimEnd('\n'))
            out.setSpan(
                TypefaceSpan(CODE_TYPEFACE), start, out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.setSpan(
                BackgroundColorSpan(CODE_BG_COLOR), start, out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.append('\n')
            codeBlock.clear()
        }

        text.split('\n').forEach { line ->
            val trimmed = line.trim()
            // 围栏代码块：``` 之间的内容原样展示（等宽 + 灰底）
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    flushCodeBlock()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                return@forEach
            }
            if (inCodeBlock) {
                codeBlock.append(line).append('\n')
                return@forEach
            }
            when {
                trimmed.isEmpty() -> out.append('\n')

                hrPattern.matches(trimmed) -> {
                    out.append("————————").append('\n')
                }

                else -> {
                    val heading = headingPattern.find(trimmed)
                    val quote = quotePattern.find(trimmed)
                    val unordered = unorderedPattern.find(trimmed)
                    val ordered = orderedPattern.find(trimmed)
                    when {
                        heading != null -> {
                            val level = heading.groupValues[1].length
                            val start = out.length
                            appendInline(out, heading.groupValues[2])
                            out.setSpan(
                                StyleSpan(Typeface.BOLD), start, out.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            out.setSpan(
                                RelativeSizeSpan(headingScale(level)), start, out.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            out.append('\n')
                        }

                        quote != null -> {
                            val start = out.length
                            out.append("▎ ")
                            appendInline(out, quote.groupValues[1])
                            out.setSpan(
                                ForegroundColorSpan(QUOTE_COLOR), start, out.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            out.append('\n')
                        }

                        unordered != null -> {
                            out.append("• ")
                            appendInline(out, unordered.groupValues[1])
                            out.append('\n')
                        }

                        ordered != null -> {
                            out.append(ordered.groupValues[1]).append(". ")
                            appendInline(out, ordered.groupValues[2])
                            out.append('\n')
                        }

                        else -> {
                            appendInline(out, trimmed)
                            out.append('\n')
                        }
                    }
                }
            }
        }
        if (inCodeBlock) flushCodeBlock()
        // 去掉结尾多余空行
        while (out.isNotEmpty() && out[out.length - 1] == '\n') {
            out.delete(out.length - 1, out.length)
        }
        return out
    }

    /**
     * 纯文本预览（列表卡片、分享文本/图片使用）：去掉 Markdown 标记
     *
     * 列表只截前 100 字，标记会占掉大量字数，这里先转纯文本再截断。
     */
    fun toPlainText(source: String?): String {
        val text = source?.takeIf { it.isNotBlank() }?.replace("\r\n", "\n") ?: return ""
        val builder = StringBuilder()
        var inCodeBlock = false
        text.split('\n').forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                return@forEach
            }
            if (inCodeBlock) {
                builder.append(line).append(' ')
                return@forEach
            }
            if (trimmed.isEmpty()) {
                builder.append('\n')
                return@forEach
            }
            val heading = headingPattern.find(trimmed)
            val quote = quotePattern.find(trimmed)
            val unordered = unorderedPattern.find(trimmed)
            val ordered = orderedPattern.find(trimmed)
            when {
                heading != null -> builder.append(inlinePlain(heading.groupValues[2]))
                quote != null -> builder.append(inlinePlain(quote.groupValues[1]))
                unordered != null -> builder.append("• ").append(inlinePlain(unordered.groupValues[1]))
                ordered != null ->
                    builder.append(ordered.groupValues[1]).append(". ")
                        .append(inlinePlain(ordered.groupValues[2]))

                hrPattern.matches(trimmed) -> builder.append('\n')
                else -> builder.append(inlinePlain(trimmed))
            }
            builder.append('\n')
        }
        return builder.toString().trim()
    }

    // ==================== 内部实现 ====================

    private fun headingScale(level: Int): Float = when (level) {
        1 -> 1.3f
        2 -> 1.2f
        3 -> 1.1f
        else -> 1.0f
    }

    /**
     * 行内渲染：粗体 / 斜体 / 行内代码 / 链接
     */
    private fun appendInline(out: SpannableStringBuilder, line: String) {
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                line.startsWith("~~", index) -> {
                    val end = line.indexOf("~~", index + 2)
                    if (end > index + 2) {
                        val start = out.length
                        out.append(line, index + 2, end)
                        out.setSpan(
                            StrikethroughSpan(), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        index = end + 2
                    } else {
                        out.append(char)
                        index++
                    }
                }

                line.startsWith("**", index) -> {
                    val end = line.indexOf("**", index + 2)
                    if (end > index + 2) {
                        val start = out.length
                        out.append(line, index + 2, end)
                        out.setSpan(
                            StyleSpan(Typeface.BOLD), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        index = end + 2
                    } else {
                        out.append(char)
                        index++
                    }
                }

                char == '`' -> {
                    val end = line.indexOf('`', index + 1)
                    if (end > index + 1) {
                        val start = out.length
                        out.append(line, index + 1, end)
                        out.setSpan(
                            TypefaceSpan(CODE_TYPEFACE), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        out.setSpan(
                            BackgroundColorSpan(CODE_BG_COLOR), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        index = end + 1
                    } else {
                        out.append(char)
                        index++
                    }
                }

                char == '*' || char == '_' -> {
                    val end = line.indexOf(char, index + 1)
                    if (end > index + 1) {
                        val start = out.length
                        out.append(line, index + 1, end)
                        out.setSpan(
                            StyleSpan(Typeface.ITALIC), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        index = end + 1
                    } else {
                        out.append(char)
                        index++
                    }
                }

                char == '[' -> {
                    val labelEnd = line.indexOf(']', index + 1)
                    val urlStart = if (labelEnd > 0) line.indexOf('(', labelEnd) else -1
                    val urlEnd = if (urlStart > 0) line.indexOf(')', urlStart) else -1
                    if (labelEnd > 0 && urlStart == labelEnd + 1 && urlEnd > urlStart + 1) {
                        val label = line.substring(index + 1, labelEnd)
                        val url = line.substring(urlStart + 1, urlEnd)
                        val start = out.length
                        out.append(label.ifEmpty { url })
                        out.setSpan(
                            URLSpan(url), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        out.setSpan(
                            ForegroundColorSpan(LINK_COLOR), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        index = urlEnd + 1
                    } else {
                        out.append(char)
                        index++
                    }
                }

                else -> {
                    out.append(char)
                    index++
                }
            }
        }
    }

    /**
     * 行内纯文本：去掉粗体/斜体/删除线/行内代码标记，链接只保留文字
     */
    private fun inlinePlain(line: String): String {
        val builder = StringBuilder()
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                line.startsWith("~~", index) -> {
                    // 成对时去掉标记，否则整对按原样保留（只回填一个会漏出 `~`）
                    if (line.indexOf("~~", index + 2) > index + 2) {
                        index += 2
                    } else {
                        builder.append("~~")
                        index += 2
                    }
                }

                line.startsWith("**", index) -> {
                    // 成对时去掉标记，否则整对按原样保留（只回填一个会漏出 `*`）
                    if (line.indexOf("**", index + 2) > index + 2) {
                        index += 2
                    } else {
                        builder.append("**")
                        index += 2
                    }
                }

                char == '`' || char == '*' || char == '_' -> {
                    if (line.indexOf(char, index + 1) > index + 1) {
                        index++
                    } else {
                        builder.append(char)
                        index++
                    }
                }

                char == '[' -> {
                    val labelEnd = line.indexOf(']', index + 1)
                    val urlStart = if (labelEnd > 0) line.indexOf('(', labelEnd) else -1
                    val urlEnd = if (urlStart > 0) line.indexOf(')', urlStart) else -1
                    if (labelEnd > 0 && urlStart == labelEnd + 1 && urlEnd > urlStart + 1) {
                        builder.append(line, index + 1, labelEnd)
                        index = urlEnd + 1
                    } else {
                        builder.append(char)
                        index++
                    }
                }

                else -> {
                    builder.append(char)
                    index++
                }
            }
        }
        return builder.toString()
    }
}
