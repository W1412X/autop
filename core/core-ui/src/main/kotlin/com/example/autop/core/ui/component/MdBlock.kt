/**
 * 轻量 Markdown 块级解析（docs/tech/08 §4.2 AssistantText，N178）：覆盖模型输出
 * 常见语法——标题 / 段落 / 引用 / 无序与有序列表 / 围栏代码块 / 分隔线；行内语法
 * 见 MarkdownInline。不做完整 CommonMark（表格等按原文渲染）。
 */
package com.example.autop.core.ui.component

/** 块级 Markdown 元素。 */
internal sealed interface MdBlock {
    /** 标题（[depth] 为 1..6）。 */
    data class Heading(
        val depth: Int,
        val text: String,
    ) : MdBlock

    /** 段落（软换行原样保留）。 */
    data class Paragraph(
        val text: String,
    ) : MdBlock

    /** 引用。 */
    data class Quote(
        val text: String,
    ) : MdBlock

    /** 列表项（[marker] 为 "•" 或 "1." 等）。 */
    data class Item(
        val marker: String,
        val text: String,
    ) : MdBlock

    /** 围栏代码块（[body] 不含围栏行）。 */
    data class Code(
        val body: String,
    ) : MdBlock

    /** 分隔线。 */
    data object Divider : MdBlock
}

/** 解析块级结构：逐行扫描，空白行分隔段落。 */
internal fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MdBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val heading = headingOf(line)
        val item = itemOf(line)
        index =
            when {
                line.isBlank() -> index + 1
                isFence(line) -> codeBlock(blocks, lines, index)
                isDivider(line) -> {
                    blocks += MdBlock.Divider
                    index + 1
                }
                heading != null -> {
                    blocks += heading
                    index + 1
                }
                quoteOf(line) != null -> quoteBlock(blocks, lines, index)
                item != null -> {
                    blocks += item
                    index + 1
                }
                else -> paragraphBlock(blocks, lines, index)
            }
    }
    return blocks
}

/** 围栏代码块：收集到闭合围栏（或文末）；语言标签丢弃。 */
private fun codeBlock(
    blocks: MutableList<MdBlock>,
    lines: List<String>,
    start: Int,
): Int {
    val body = mutableListOf<String>()
    var index = start + 1
    while (index < lines.size && !isFence(lines[index])) {
        body += lines[index]
        index += 1
    }
    blocks += MdBlock.Code(body.joinToString("\n"))
    return (index + 1).coerceAtMost(lines.size)
}

/** 引用块：收集连续 `>` 行，去掉行首标记。 */
private fun quoteBlock(
    blocks: MutableList<MdBlock>,
    lines: List<String>,
    start: Int,
): Int {
    val body = mutableListOf<String>()
    var index = start
    while (index < lines.size && quoteOf(lines[index]) != null) {
        body += quoteOf(lines[index]).orEmpty()
        index += 1
    }
    blocks += MdBlock.Quote(body.joinToString("\n"))
    return index
}

/** 段落：收集连续普通行，软换行原样保留。 */
private fun paragraphBlock(
    blocks: MutableList<MdBlock>,
    lines: List<String>,
    start: Int,
): Int {
    val body = mutableListOf<String>()
    var index = start
    while (index < lines.size && !isBlockStart(lines[index])) {
        body += lines[index]
        index += 1
    }
    blocks += MdBlock.Paragraph(body.joinToString("\n"))
    return index
}

private fun headingOf(line: String): MdBlock.Heading? {
    val match = HEADING.matchEntire(line.trimStart()) ?: return null
    val depth = match.groupValues[0].takeWhile { it == '#' }.length
    return MdBlock.Heading(depth, match.groupValues[1].trim())
}

private fun itemOf(line: String): MdBlock.Item? {
    val trimmed = line.trimStart()
    val bullet = BULLET.matchEntire(trimmed)
    val numbered = NUMBERED.matchEntire(trimmed)
    return when {
        bullet != null -> MdBlock.Item(BULLET_MARKER, bullet.groupValues[1].trim())
        numbered != null -> MdBlock.Item("${numbered.groupValues[1]}.", numbered.groupValues[2].trim())
        else -> null
    }
}

/** 引用行正文（去 `>` 前缀）；非引用行返回 null。 */
private fun quoteOf(line: String): String? =
    line
        .trimStart()
        .takeIf { it.startsWith(QUOTE_PREFIX) }
        ?.removePrefix(QUOTE_PREFIX)
        ?.trimStart()

private fun isFence(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith(FENCE) || trimmed.startsWith(ALT_FENCE)
}

private fun isDivider(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.length >= DIVIDER_MIN_LEN && trimmed.toSet().size == 1 && trimmed[0] in DIVIDER_CHARS
}

private fun isBlockStart(line: String): Boolean =
    line.isBlank() ||
        isFence(line) ||
        isDivider(line) ||
        headingOf(line) != null ||
        quoteOf(line) != null ||
        itemOf(line) != null

private const val QUOTE_PREFIX: String = ">"
private const val FENCE: String = "```"
private const val ALT_FENCE: String = "~~~"
private const val BULLET_MARKER: String = "•"
private const val DIVIDER_MIN_LEN: Int = 3
private val DIVIDER_CHARS: Set<Char> = setOf('-', '*', '_')
private val HEADING: Regex = Regex("#{1,6}\\s+(.+)")
private val BULLET: Regex = Regex("[-*+]\\s+(.+)")
private val NUMBERED: Regex = Regex("(\\d{1,3})[.)]\\s+(.+)")
