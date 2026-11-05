/**
 * 行内 Markdown → AnnotatedString（docs/tech/08 §4.2 AssistantText，N178）：
 * 粗体 / 斜体 / 行内代码 / 删除线 / 链接文本；标记外文本原样保留，未闭合标记按原文。
 * 流式增量渲染用 [trimUnclosedTail] 先把半截标记收口，避免源码裸显。
 */
package com.example.autop.core.ui.component

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * 行内语法合成（[code] / [link] 为对应 SpanStyle）。
 * 长标记优先匹配，前后词边界避免 `snake_case` 被误判为斜体。
 */
internal fun inlineMarkdown(
    text: String,
    code: SpanStyle,
    link: SpanStyle,
): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        for (match in INLINE_TOKENS.findAll(text)) {
            append(text.substring(index, match.range.first))
            withStyle(styleOf(match.value, code, link)) {
                append(inlineText(match.value))
            }
            index = match.range.last + 1
        }
        append(text.substring(index))
    }

/** 标记 → 强调样式。 */
private fun styleOf(
    token: String,
    code: SpanStyle,
    link: SpanStyle,
): SpanStyle =
    when {
        token.startsWith("`") -> code
        token.startsWith("~~") -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        token.startsWith("**") || token.startsWith("__") -> SpanStyle(fontWeight = FontWeight.Bold)
        token.startsWith("[") -> link
        else -> SpanStyle(fontStyle = FontStyle.Italic)
    }

/** 去掉标记取正文（链接取标签文本）。 */
private fun inlineText(token: String): String =
    when {
        token.startsWith("[") -> token.substring(1, token.indexOf(']'))
        token.startsWith("**") || token.startsWith("__") || token.startsWith("~~") ->
            token.substring(2, token.length - 2)
        else -> token.substring(1, token.length - 1)
    }

/** 行内语法（长标记优先；斜体要求词边界，避免误判 snake_case）。 */
private val INLINE_TOKENS: Regex =
    Regex(
        """`[^`\n]+`|\*\*[^*\n]+\*\*|__[^_\n]+__|~~[^~\n]+~~|""" +
            """(?<![\w*])\*[^*\s\n][^*\n]*\*(?!\*)|(?<![\w_])_[^_\s\n][^_\n]*_(?!\w)|""" +
            """\[[^\]\n]+]\([^)\s\n]+\)""",
    )

/**
 * 行内起始标记（长标记优先，起始约束同 [INLINE_TOKENS]）。单字符 `*` / `_` 不在列：
 * 与数学式、变量名等正文无法区分，截断会误伤，宁可让半截标记短暂裸显。
 */
private val INLINE_OPENERS: List<Regex> =
    listOf(
        Regex("\\*\\*(?=[^*\\s])"),
        Regex("__(?=[^_\\s])"),
        Regex("~~(?=[^~\\s])"),
        Regex("`(?=[^`\\s])"),
        Regex("\\[(?=[^\\]\\s])"),
    )

/** 未找到未闭合标记。 */
private const val NO_CUT: Int = -1

/**
 * 流式收口：最后一块里行内未闭合的半截标记（`**粗`、`` `码 ``、`[链](` 等）连同其后
 * 文本一起截掉，等闭合标记到达再整体显形，避免源码裸显。代码块等非行内块原样保留。
 */
internal fun List<MdBlock>.trimUnclosedTail(): List<MdBlock> {
    val last = lastOrNull() ?: return this
    val trimmed =
        when (last) {
            is MdBlock.Heading -> last.copy(text = trimUnclosedInline(last.text))
            is MdBlock.Paragraph -> last.copy(text = trimUnclosedInline(last.text))
            is MdBlock.Quote -> last.copy(text = trimUnclosedInline(last.text))
            is MdBlock.Item -> last.copy(text = trimUnclosedInline(last.text))
            else -> last
        }
    return dropLast(1) + trimmed
}

/** 逐行找最早的未闭合起始标记，从那里截断（行内标记不跨行，逐行判定即完备）。 */
private fun trimUnclosedInline(text: String): String {
    var offset = 0
    for (line in text.split('\n')) {
        val cut = unclosedIndex(line)
        if (cut != NO_CUT) return text.substring(0, offset + cut)
        offset += line.length + 1
    }
    return text
}

/** 最早的“未被语法匹配、之后也没有收口标记”的起始标记位置；没有则 [NO_CUT]。 */
private fun unclosedIndex(line: String): Int {
    val closed = INLINE_TOKENS.findAll(line).map { it.range }
    val starts = INLINE_OPENERS.flatMap { opener -> opener.findAll(line).map { m -> m.range.first } }
    val outside = starts.filter { start -> closed.none { range -> start >= range.first && start <= range.last } }
    val unclosed = outside.filter { start -> !hasCloser(line, start) }
    return unclosed.minOrNull() ?: NO_CUT
}

/** 起始标记之后是否已有收口标记（`[` 要区分正文方括号与链接在建）。 */
private fun hasCloser(
    line: String,
    start: Int,
): Boolean =
    when {
        line.startsWith("[", start) -> linkClosed(line, start)
        line.startsWith("**", start) -> line.indexOf("**", start + 2) >= 0
        line.startsWith("__", start) -> line.indexOf("__", start + 2) >= 0
        line.startsWith("~~", start) -> line.indexOf("~~", start + 2) >= 0
        else -> line.indexOf(line[start], start + 1) >= 0
    }

/** 链接收口：`[` 后没有 `]`＝标签在建；`](` 后没有 `)`＝地址在建；其余是正文方括号。 */
private fun linkClosed(
    line: String,
    start: Int,
): Boolean {
    val labelEnd = line.indexOf(']', start)
    return when {
        labelEnd < 0 -> false
        !line.startsWith("](", labelEnd) -> true
        else -> line.indexOf(')', labelEnd + 2) >= 0
    }
}
