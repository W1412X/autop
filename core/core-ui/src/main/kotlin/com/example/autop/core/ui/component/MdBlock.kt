/**
 * 轻量 Markdown 块级解析（docs/tech/08 §4.2 AssistantText，N178）：覆盖模型输出
 * 常见语法——标题 / 段落 / 引用 / 无序与有序列表 / 围栏代码块 / 分隔线 / 表格；行内语法
 * 见 MarkdownInline。
 */
package com.example.autop.core.ui.component

/** 表格列对齐（`:---` / `:---:` / `---:`）。 */
internal enum class MdAlign {
    START,
    CENTER,
    END,
}

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

    /** 列表项（[marker] 为 "•"、"1."、"☑" 等）。 */
    data class Item(
        val marker: String,
        val text: String,
    ) : MdBlock

    /** 围栏代码块（[body] 不含围栏行）。 */
    data class Code(
        val body: String,
    ) : MdBlock

    /** 表格：表头、列对齐与数据行（单元格缺列补空串）。 */
    data class Table(
        val headers: List<String>,
        val aligns: List<MdAlign>,
        val rows: List<List<String>>,
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
                tableAt(lines, index) -> tableBlock(blocks, lines, index)
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
    while (index < lines.size && !isBlockStart(lines, index)) {
        body += lines[index]
        index += 1
    }
    blocks += MdBlock.Paragraph(body.joinToString("\n"))
    return index
}

/** 表格：当前行含 `|`，下一行是列数一致的分隔行（`|---|:---:|`）。 */
private fun tableAt(
    lines: List<String>,
    index: Int,
): Boolean {
    val header = lines.getOrNull(index) ?: return false
    if (!header.contains(PIPE)) return false
    val aligns = lines.getOrNull(index + 1)?.let(::alignsOf) ?: return false
    return aligns.size == cellsOf(header).size
}

/** 表格块：表头 + 分隔行 + 连续数据行（多出的单元格丢弃，缺列补空串）。 */
private fun tableBlock(
    blocks: MutableList<MdBlock>,
    lines: List<String>,
    start: Int,
): Int {
    val headers = cellsOf(lines[start])
    val aligns = alignsOf(lines[start + 1]) ?: return start + 1
    var index = start + 2
    val rows = mutableListOf<List<String>>()
    while (index < lines.size && lines[index].isNotBlank() && lines[index].contains(PIPE)) {
        val cells = cellsOf(lines[index])
        rows += List(headers.size) { column -> cells.getOrElse(column) { "" } }
        index += 1
    }
    blocks += MdBlock.Table(headers = headers, aligns = aligns, rows = rows)
    return index
}

/** 按 `|` 拆单元格（去掉首尾竖线）；非表格分隔行返回 null。 */
private fun cellsOf(line: String): List<String> =
    line
        .trim()
        .removePrefix(PIPE_TEXT)
        .removeSuffix(PIPE_TEXT)
        .split(PIPE)
        .map { it.trim() }

/** 分隔行 → 各列对齐；任一分格不是 `:?-+:?` 则不是表格分隔行。 */
private fun alignsOf(line: String): List<MdAlign>? {
    val cells = cellsOf(line)
    if (cells.isEmpty() || cells.any { it.isEmpty() }) return null
    return cells.map { cell ->
        val match = TABLE_DELIMITER.matchEntire(cell) ?: return null
        val left = match.groupValues[1] == COLON
        val right = match.groupValues[2] == COLON
        when {
            left && right -> MdAlign.CENTER
            right -> MdAlign.END
            else -> MdAlign.START
        }
    }
}

private fun headingOf(line: String): MdBlock.Heading? {
    val match = HEADING.matchEntire(line.trimStart()) ?: return null
    return MdBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
}

private fun itemOf(line: String): MdBlock.Item? {
    val trimmed = line.trimStart()
    val bullet = BULLET.matchEntire(trimmed)
    val numbered = NUMBERED.matchEntire(trimmed) ?: NUMBERED_CN.matchEntire(trimmed)
    return when {
        bullet != null -> bulletItem(bullet.groupValues[1].trim())
        numbered != null -> MdBlock.Item("${numbered.groupValues[1]}.", numbered.groupValues[2].trim())
        else -> null
    }
}

/** 无序项内容 → 列表项（`[x]` / `[ ]` 开头转勾选标记）。 */
private fun bulletItem(content: String): MdBlock.Item {
    TASK_CHECKED.matchEntire(content)?.let { return MdBlock.Item(CHECKED_MARKER, it.groupValues[1].trim()) }
    TASK_UNCHECKED.matchEntire(content)?.let { return MdBlock.Item(UNCHECKED_MARKER, it.groupValues[1].trim()) }
    return MdBlock.Item(BULLET_MARKER, content)
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

private fun isBlockStart(
    lines: List<String>,
    index: Int,
): Boolean {
    val line = lines[index]
    return line.isBlank() ||
        isFence(line) ||
        isDivider(line) ||
        headingOf(line) != null ||
        quoteOf(line) != null ||
        tableAt(lines, index) ||
        itemOf(line) != null
}

private const val QUOTE_PREFIX: String = ">"
private const val FENCE: String = "```"
private const val ALT_FENCE: String = "~~~"
private const val PIPE: Char = '|'
private const val PIPE_TEXT: String = "|"
private const val COLON: String = ":"
private const val BULLET_MARKER: String = "•"
private const val CHECKED_MARKER: String = "☑"
private const val UNCHECKED_MARKER: String = "☐"
private const val DIVIDER_MIN_LEN: Int = 3
private val DIVIDER_CHARS: Set<Char> = setOf('-', '*', '_')
private val HEADING: Regex = Regex("(#{1,6})(?!#)\\s*(.+)")
private val BULLET: Regex = Regex("[-*+]\\s+(.+)")
private val NUMBERED: Regex = Regex("(\\d{1,3})[.)]\\s+(.+)")
private val NUMBERED_CN: Regex = Regex("(\\d{1,3})、\\s*(.+)")
private val TABLE_DELIMITER: Regex = Regex("(:?)-+(:?)")
private val TASK_CHECKED: Regex = Regex("\\[[xX]]\\s*(.*)")
private val TASK_UNCHECKED: Regex = Regex("\\[\\s?]\\s*(.*)")
