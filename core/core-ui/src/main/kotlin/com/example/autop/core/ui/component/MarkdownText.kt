/**
 * Markdown 文本（docs/tech/08 §4.2 AssistantText，N178）：[parseMarkdown] 逐块渲染，
 * 标题 / 引用 / 列表 / 代码块 / 分隔线各有样式，行内语法见 [inlineMarkdown]。
 * 流式文本同路渲染：半截行内标记由 [trimUnclosedTail] 收口，末尾补闪烁光标。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.LocalSpacing

/**
 * Markdown 文本。
 *
 * @param text Markdown 原文。
 * @param modifier 修饰符。
 * @param color 正文颜色（Unspecified 跟随主题）。
 * @param style 正文样式（标题 / 代码块在此基础上变体）。
 * @param streaming 流式未落盘：半截标记收口 + 末尾闪烁光标。
 * @param cursorAlpha 流式光标透明度（闪烁动效由调用方驱动）。
 */
@Composable
public fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    streaming: Boolean = false,
    cursorAlpha: Float = 1f,
) {
    val blocks =
        remember(text, streaming) {
            val parsed = parseMarkdown(text)
            if (streaming) parsed.trimUnclosedTail() else parsed
        }
    val code =
        SpanStyle(
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            background = MaterialTheme.colorScheme.surfaceVariant,
        )
    val link =
        SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    val cursor = streamCursor(streaming = streaming, cursorAlpha = cursorAlpha)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        if (blocks.isEmpty() && cursor != null) {
            Text(text = cursor, style = style, color = color)
        }
        for ((index, block) in blocks.withIndex()) {
            val suffix = if (index == blocks.lastIndex) cursor else null
            MarkdownBlockView(block = block, code = code, link = link, color = color, style = style, suffix = suffix)
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MdBlock,
    code: SpanStyle,
    link: SpanStyle,
    color: Color,
    style: TextStyle,
    suffix: AnnotatedString?,
) {
    when (block) {
        is MdBlock.Heading ->
            Text(
                text = inlineMarkdown(block.text, code, link).withSuffix(suffix),
                style = headingStyle(block.depth, style),
                color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
            )
        is MdBlock.Paragraph ->
            Text(
                text = inlineMarkdown(block.text, code, link).withSuffix(suffix),
                style = style,
                color = color,
            )
        is MdBlock.Quote -> QuoteView(block = block, code = code, link = link, style = style, suffix = suffix)
        is MdBlock.Item -> ItemView(block = block, code = code, link = link, style = style, suffix = suffix)
        is MdBlock.Code -> CodeView(block = block, suffix = suffix)
        is MdBlock.Table -> TableView(block = block, code = code, link = link, style = style, suffix = suffix)
        MdBlock.Divider -> {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (suffix != null) Text(text = suffix, style = style, color = color)
        }
    }
}

/** 引用：左侧色条 + 斜体正文。 */
@Composable
private fun QuoteView(
    block: MdBlock.Quote,
    code: SpanStyle,
    link: SpanStyle,
    style: TextStyle,
    suffix: AnnotatedString?,
) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier =
                Modifier
                    .width(QuoteBarWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Text(
            text = inlineMarkdown(block.text, code, link).withSuffix(suffix),
            style = style.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = LocalSpacing.current.sm),
        )
    }
}

/** 列表项：标记 + 正文。 */
@Composable
private fun ItemView(
    block: MdBlock.Item,
    code: SpanStyle,
    link: SpanStyle,
    style: TextStyle,
    suffix: AnnotatedString?,
) {
    Row {
        Text(
            text = block.marker,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = inlineMarkdown(block.text, code, link).withSuffix(suffix),
            style = style,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = LocalSpacing.current.sm),
        )
    }
}

/** 代码块：等宽字体 + 浅底圆角。 */
@Composable
private fun CodeView(
    block: MdBlock.Code,
    suffix: AnnotatedString?,
) {
    Text(
        text = AnnotatedString(block.body).withSuffix(suffix),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
                    shape = MaterialTheme.shapes.small,
                ).padding(all = LocalSpacing.current.sm),
    )
}

/**
 * 表格：列宽均分可用宽度（低于 [TableMinCellWidth] 时横向滚动），表头加粗浅底、
 * 行间细分隔线；流式光标落在最后一个单元格。
 */
@Composable
private fun TableView(
    block: MdBlock.Table,
    code: SpanStyle,
    link: SpanStyle,
    style: TextStyle,
    suffix: AnnotatedString?,
) {
    val columns = block.headers.size
    if (columns == 0) return
    val cellStyle = style.copy(fontSize = style.fontSize * TABLE_FONT_SCALE)
    BoxWithConstraints {
        val cellWidth = (maxWidth / columns).coerceAtLeast(TableMinCellWidth)
        val tableWidth = cellWidth * columns
        Column(
            modifier =
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
                        shape = MaterialTheme.shapes.small,
                    ).clip(MaterialTheme.shapes.small)
                    .horizontalScroll(rememberScrollState()),
        ) {
            TableRowView(
                cells = block.headers,
                aligns = block.aligns,
                cellWidth = cellWidth,
                cellStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                header = true,
                suffix = if (block.rows.isEmpty()) suffix else null,
                code = code,
                link = link,
            )
            block.rows.forEachIndexed { rowIndex, cells ->
                HorizontalDivider(
                    modifier = Modifier.width(tableWidth),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                TableRowView(
                    cells = cells,
                    aligns = block.aligns,
                    cellWidth = cellWidth,
                    cellStyle = cellStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    header = false,
                    suffix = if (rowIndex == block.rows.lastIndex) suffix else null,
                    code = code,
                    link = link,
                )
            }
        }
    }
}

/** 表格一行：定宽单元格，按列对齐；[header] 用表头底色。 */
@Composable
private fun TableRowView(
    cells: List<String>,
    aligns: List<MdAlign>,
    cellWidth: Dp,
    cellStyle: TextStyle,
    color: Color,
    header: Boolean,
    suffix: AnnotatedString?,
    code: SpanStyle,
    link: SpanStyle,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .width(cellWidth * cells.size)
                .background(
                    if (header) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                ),
        verticalAlignment = Alignment.Top,
    ) {
        cells.forEachIndexed { column, cell ->
            Text(
                text = inlineMarkdown(cell, code, link).withSuffix(if (column == cells.lastIndex) suffix else null),
                style = cellStyle,
                color = color,
                textAlign = aligns.getOrElse(column) { MdAlign.START }.textAlign(),
                modifier =
                    Modifier
                        .width(cellWidth)
                        .padding(horizontal = spacing.sm, vertical = spacing.xs),
            )
        }
    }
}

private fun MdAlign.textAlign(): TextAlign =
    when (this) {
        MdAlign.START -> TextAlign.Start
        MdAlign.CENTER -> TextAlign.Center
        MdAlign.END -> TextAlign.End
    }

/** 标题档位：正文 [base] 上按 [H1_SIZE_FACTOR] / [H2_SIZE_FACTOR] / [H3_SIZE_FACTOR] 放大，
 * 随全局字号缩放一起变化，避免固定 title token 显得过大。
 */
private fun headingStyle(
    depth: Int,
    base: TextStyle,
): TextStyle {
    val factor =
        when (depth) {
            1 -> H1_SIZE_FACTOR
            2 -> H2_SIZE_FACTOR
            else -> H3_SIZE_FACTOR
        }
    return base.copy(
        fontSize = base.fontSize * factor,
        lineHeight = base.lineHeight * factor,
        fontWeight = if (depth <= 2) FontWeight.SemiBold else FontWeight.Medium,
    )
}

/** 流式光标段（闪烁透明度由调用方传入）；非流式无光标。 */
@Composable
private fun streamCursor(
    streaming: Boolean,
    cursorAlpha: Float,
): AnnotatedString? =
    if (streaming) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha))) {
                append(STREAM_CURSOR_GLYPH)
            }
        }
    } else {
        null
    }

/** 末尾补流式光标段。 */
private fun AnnotatedString.withSuffix(suffix: AnnotatedString?): AnnotatedString =
    if (suffix == null) this else this + suffix

private val QuoteBarWidth = 2.dp

/** 表格单元格最小宽度：低于它时整表横向滚动，避免文字被挤成竖排。 */
private val TableMinCellWidth = 88.dp

/** 表格正文相对正文样式的缩小比例。 */
private const val TABLE_FONT_SCALE: Float = 0.92f

private const val STREAM_CURSOR_GLYPH: String = "▍"

private const val H1_SIZE_FACTOR: Float = 1.35f

private const val H2_SIZE_FACTOR: Float = 1.15f

private const val H3_SIZE_FACTOR: Float = 1.05f
