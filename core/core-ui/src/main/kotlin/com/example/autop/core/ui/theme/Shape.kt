/**
 * 圆角 token（docs/tech/08 §1.3）：按钮、Chip、输入框 8dp；卡片、面板、截图缩略图 12dp；
 * 底部弹层顶部 28dp。UI 层不写圆角字面量。
 */
package com.example.autop.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 全局形状（[AutopTheme] 注入 MaterialTheme）。 */
public val AutopShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
