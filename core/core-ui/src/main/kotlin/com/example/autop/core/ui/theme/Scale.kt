/**
 * 间距与图标尺寸 token（docs/tech/08 §1.3）。
 *
 * feature 代码不允许出现 dp 字面量：动态间距取 [LocalSpacing]，
 * 组件内部固定尺寸取 [AutopDimens]，图标尺寸取 [IconSize] 并乘 [LocalIconScale]。
 */
package com.example.autop.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 间距组（随布局密度变化）。 */
@Immutable
public data class Spacing(
    public val xs: Dp,
    public val sm: Dp,
    public val md: Dp,
    public val lg: Dp,
    public val xl: Dp,
    public val screenEdge: Dp,
    public val listGap: Dp,
) {
    public companion object {
        /** 紧凑。 */
        public val COMPACT: Spacing =
            Spacing(
                xs = 2.dp,
                sm = 4.dp,
                md = 8.dp,
                lg = 12.dp,
                xl = 16.dp,
                screenEdge = 12.dp,
                listGap = 6.dp,
            )

        /** 标准（默认）。 */
        public val STANDARD: Spacing =
            Spacing(
                xs = 4.dp,
                sm = 8.dp,
                md = 12.dp,
                lg = 16.dp,
                xl = 24.dp,
                screenEdge = 16.dp,
                listGap = 10.dp,
            )

        /** 宽松。 */
        public val COMFORTABLE: Spacing =
            Spacing(
                xs = 6.dp,
                sm = 10.dp,
                md = 16.dp,
                lg = 24.dp,
                xl = 32.dp,
                screenEdge = 20.dp,
                listGap = 14.dp,
            )
    }
}

/** 当前间距（[AutopTheme] 按外观设置提供）。 */
public val LocalSpacing: ProvidableCompositionLocal<Spacing> =
    staticCompositionLocalOf { Spacing.STANDARD }

/** 图标基准尺寸档位（实际尺寸乘 [LocalIconScale]）。 */
public enum class IconSize(
    public val base: Dp,
) {
    Xs(14.dp),
    Sm(18.dp),
    Md(24.dp),
    Lg(32.dp),
    Xl(48.dp),
    Xxl(96.dp),
}

/** 图标缩放（外观设置 0.9 / 1.0 / 1.15）。 */
public val LocalIconScale: ProvidableCompositionLocal<Float> = staticCompositionLocalOf { 1f }

/** 该档位在当前图标缩放下的实际尺寸。 */
@Composable
public fun IconSize.scaled(): Dp = base * LocalIconScale.current
