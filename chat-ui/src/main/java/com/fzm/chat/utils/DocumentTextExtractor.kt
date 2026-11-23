package com.fzm.chat.utils

import java.io.File
import java.util.zip.ZipFile

/**
 * 本地文档内容提取（不依赖第三方 Office SDK）。
 *
 * 支持：
 * - docx：解析 word/document.xml 的段落与表格
 * - xlsx：解析 sharedStrings.xml + 工作表，输出制表符分隔的表格文本
 * - pptx：按页提取每页文字
 * - txt / csv / log / json / xml / md：直接读取（UTF-8，失败回退 GBK）
 *
 * 不支持二进制老格式（doc / xls / ppt），这类交由「用其他应用打开」。
 */
object DocumentTextExtractor {

    /** 由本类渲染成文本的扩展名 */
    val TEXT_TYPES = setOf("docx", "xlsx", "pptx", "txt", "csv", "log", "json", "xml", "md", "ini", "conf")

    /** 交给 PdfRenderer 渲染的扩展名 */
    const val PDF = "pdf"

    /** 是否支持内置预览 */
    fun isSupported(ext: String): Boolean = ext == PDF || ext in TEXT_TYPES

    /** 单文档最大字符数，防止超大表格/文档把界面卡死 */
    private const val MAX_CHARS = 300_000

    fun extract(file: File, ext: String): String? = when (ext) {
        "docx" -> docx(file)
        "xlsx" -> xlsx(file)
        "pptx" -> pptx(file)
        in TEXT_TYPES -> readText(file)
        else -> null
    }?.let { if (it.length > MAX_CHARS) it.substring(0, MAX_CHARS) + "\n\n…（内容过长，已截断）" else it }

    // ---------------------------------------------------------------- docx

    private fun docx(file: File): String? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return null
            val xml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            xmlToPlainText(xml)
        }
    }.getOrNull()

    private fun xmlToPlainText(xml: String): String {
        var s = xml
        s = s.replace("<w:tab/>", "\t")
            .replace("<w:tab></w:tab>", "\t")
            .replace("<w:br/>", "\n")
            .replace("<w:br></w:br>", "\n")
        // 表格单元格用 | 分隔，行结束换行
        s = s.replace("</w:tc>", " | ")
            .replace("</w:tr>", "\n")
            .replace("</w:p>", "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = unescape(s)
        // 去掉每行末尾多余的 " | "
        s = s.replace(Regex("[ \\t]*\\|[ \\t]*(?=\\n)"), "")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    // ---------------------------------------------------------------- xlsx

    private fun xlsx(file: File): String? = runCatching {
        ZipFile(file).use { zip ->
            val shared = zip.getEntry("xl/sharedStrings.xml")?.let {
                zip.getInputStream(it).readBytes().toString(Charsets.UTF_8)
            }.orEmpty()
            val strings = if (shared.isEmpty()) {
                emptyList()
            } else {
                Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(shared).map { si ->
                    Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(si.groupValues[1])
                        .joinToString("") { unescape(it.groupValues[1]) }
                }.toList()
            }
            val sheetEntry = zip.getEntry("xl/worksheets/sheet1.xml")
                ?: zip.entries().asSequence().firstOrNull { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                ?: return null
            val sheet = zip.getInputStream(sheetEntry).readBytes().toString(Charsets.UTF_8)
            sheetToText(sheet, strings)
        }
    }.getOrNull()

    private fun sheetToText(sheet: String, strings: List<String>): String {
        val rows = mutableListOf<Map<Int, String>>()
        var maxCol = 0
        Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(sheet).forEach { row ->
            val cells = mutableMapOf<Int, String>()
            Regex("<c\\b([^>]*)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).forEach { c ->
                val attrs = c.groupValues[1]
                val body = c.groupValues[2]
                val ref = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1) ?: return@forEach
                val col = colIndex(ref)
                val value = when {
                    attrs.contains("t=\"s\"") -> {
                        val idx = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
                            .find(body)?.groupValues?.get(1)?.toIntOrNull()
                        idx?.let { strings.getOrNull(it) }.orEmpty()
                    }
                    attrs.contains("t=\"inlineStr\"") ->
                        Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).find(body)
                            ?.groupValues?.get(1)?.let { unescape(it) }.orEmpty()
                    else ->
                        Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)
                            ?.groupValues?.get(1)?.let { unescape(it) }.orEmpty()
                }
                if (value.isNotBlank()) {
                    cells[col] = value
                    if (col > maxCol) maxCol = col
                }
            }
            rows.add(cells)
        }
        if (rows.isEmpty()) return ""
        val sb = StringBuilder()
        rows.take(3000).forEach { cells ->
            val line = (0..maxCol.coerceAtMost(50)).joinToString("\t") { cells[it].orEmpty() }
            sb.append(line.trimEnd()).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** 列号字母转 0 基索引：A->0, Z->25, AA->26 */
    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    // ---------------------------------------------------------------- pptx

    private fun pptx(file: File): String? = runCatching {
        ZipFile(file).use { zip ->
            val slides = zip.entries().asSequence()
                .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { Regex("(\\d+)").find(it.name)?.value?.toIntOrNull() ?: 0 }
                .toList()
            if (slides.isEmpty()) return null
            buildString {
                slides.forEachIndexed { index, entry ->
                    val xml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                    val texts = Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(xml)
                        .map { unescape(it.groupValues[1]) }
                        .filter { it.isNotBlank() }
                        .toList()
                    append("── 第 ").append(index + 1).append(" 页 ──\n")
                    append(texts.joinToString("\n"))
                    append("\n\n")
                }
            }.trim()
        }
    }.getOrNull()

    // ---------------------------------------------------------------- text

    private fun readText(file: File): String? = runCatching {
        val bytes = file.readBytes()
        val utf8 = bytes.toString(Charsets.UTF_8)
        // 含替换字符说明不是 UTF-8，按 GBK 再解一次
        if (utf8.contains('\uFFFD')) bytes.toString(charset("GBK")) else utf8
    }.getOrNull()

    // ---------------------------------------------------------------- utils

    private fun unescape(s: String): String {
        var out = s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
        // 数字实体 &#x4E2D; / &#20013;
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) {
            val code = it.groupValues[1].toIntOrNull(16)
            if (code != null) String(Character.toChars(code)) else it.value
        }
        out = Regex("&#(\\d+);").replace(out) {
            val code = it.groupValues[1].toIntOrNull()
            if (code != null) String(Character.toChars(code)) else it.value
        }
        return out
    }
}
