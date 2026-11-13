/**
 * 组件固定尺寸常量（docs/tech/08 §1.4 与 §2）：按钮高度、底栏高度等不随
 * 密度/字号缩放的硬尺寸。UI 层不写 dp 字面量，固定尺寸一律取自这里。
 */
package com.example.autop.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 组件尺寸常量表。 */
public object AutopDimens {
    // ── §1.4 通用 ───────────────────────────────────────────────
    public val buttonHeight: Dp = 44.dp
    public val buttonPaddingH: Dp = 16.dp
    public val buttonIconGap: Dp = 6.dp
    public val iconButtonSize: Dp = 44.dp
    public val chipHeight: Dp = 34.dp
    public val inputHeight: Dp = 44.dp
    public val inputPaddingH: Dp = 12.dp
    public val navBarHeight: Dp = 64.dp
    public val sectionBarWidth: Dp = 4.dp
    public val sectionBarHeight: Dp = 18.dp
    public val sectionBarCornerRadius: Dp = 2.dp
    public val progressBarHeight: Dp = 3.dp
    public val listThumb: Dp = 48.dp

    /** 对话附图缩略图边长（输入区待发送图与气泡内已发送图）。 */
    public val attachThumb: Dp = 72.dp
    public val overlayBall: Dp = 52.dp
    public val overlayPanelWidth: Dp = 300.dp
    public val minTouchTarget: Dp = 48.dp

    // ── §2 组件内部固定尺寸 ─────────────────────────────────────

    /** StatusChip 高度与圆角（extraSmall 与 small 之间，按规格固定 6dp）。 */
    public val statusChipHeight: Dp = 24.dp
    public val statusChipCornerRadius: Dp = 6.dp

    /** OptionSheet 选中勾。 */
    public val optionCheckSize: Dp = 18.dp

    /** OptionSheet 选项前置图标块与行间距。 */
    public val optionIconTile: Dp = 36.dp
    public val optionTagHeight: Dp = 20.dp
    public val optionRowGap: Dp = 2.dp

    /** 加载圈与 PagedList 尾部行高。 */
    public val loadingIndicatorSize: Dp = 28.dp
    public val pagedFooterHeight: Dp = 56.dp

    /** PagedList 尾部自动加载提前量（项）。 */
    public const val AUTOLOAD_AHEAD: Int = 6

    /** TopLevelHeader 内边距（start 24 / end 12 / top 32 / bottom 20）。 */
    public val headerPaddingStart: Dp = 24.dp
    public val headerPaddingEnd: Dp = 12.dp
    public val headerPaddingTop: Dp = 32.dp
    public val headerPaddingBottom: Dp = 20.dp

    /** 空/错/加载状态块内边距与图文间距。 */
    public val statePadding: Dp = 32.dp
    public val stateGap: Dp = 12.dp

    /** InlineBanner 关闭按钮触控区。 */
    public val bannerCloseSize: Dp = 24.dp

    /** 工具自测调试图（N208）：缩略与点选放大高度。 */
    public val debugImageThumbHeight: Dp = 96.dp
    public val debugImageExpandedHeight: Dp = 420.dp

    /** 时间滚轮选择器：列宽与行高。 */
    public val wheelColumnWidth: Dp = 56.dp
    public val wheelItemHeight: Dp = 36.dp
}
