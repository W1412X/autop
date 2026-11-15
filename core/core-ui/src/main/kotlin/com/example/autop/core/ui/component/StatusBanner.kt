/**
 * 状态横幅（docs/tech/08 §4.2 StatusBanner）：暂停 / 拦截 / 等待回答 / 中断等
 * 运行状态提示，也承载 §4.1 无障碍未开启等整块提示。语义色底（容器色）、
 * medium 圆角、内边距 md；图标 + 标题/说明 + 操作按钮行。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSemanticColors
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.core.ui.theme.scaled
import com.example.autop.core.ui.theme.tone

/**
 * 状态横幅。
 *
 * @param tone 语义色系（容器色为底、前景色为文字与图标）。
 * @param message 说明文字（有 [title] 时 bodySmall，无 [title] 时 bodyMedium，
 * 对应 docs/tech/08 §4.1 双行提示与 §4.2 单行状态两种排版）。
 * @param modifier 组合修饰。
 * @param title 标题（titleSmall；null 表示无标题的单行说明式横幅）。
 * @param actions 操作按钮行（放 [AutopTonalButton] 等；null 表示无操作）。
 */
@Composable
public fun StatusBanner(
    tone: StatusTone,
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    compact: Boolean = false,
) {
    if (compact) {
        CompactStatusBanner(tone = tone, message = message, modifier = modifier, actions = actions)
        return
    }
    val semantic = LocalSemanticColors.current.tone(tone)
    Row(
        modifier =
            modifier
                .background(color = semantic.container, shape = MaterialTheme.shapes.medium)
                .padding(LocalSpacing.current.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = statusToneIcon(tone),
            contentDescription = null,
            modifier = Modifier.size(IconSize.Sm.scaled()),
            tint = semantic.foreground,
        )
        Spacer(modifier = Modifier.width(LocalSpacing.current.sm))
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = semantic.foreground,
                )
            }
            Text(
                text = message,
                style =
                    if (title != null) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                color = semantic.foreground,
            )
            if (actions != null) {
                Row(
                    modifier = Modifier.padding(top = LocalSpacing.current.sm),
                    horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
                ) {
                    actions()
                }
            }
        }
    }
}

/** 紧凑横幅：低透明度底、单行，操作按钮在右侧；用于记忆条目等信息密集的页面。 */
@Composable
private fun CompactStatusBanner(
    tone: StatusTone,
    message: String,
    modifier: Modifier,
    actions: (@Composable RowScope.() -> Unit)?,
) {
    val semantic = LocalSemanticColors.current.tone(tone)
    val spacing = LocalSpacing.current
    Row(
        modifier =
            modifier
                .background(
                    color = semantic.foreground.copy(alpha = AutopAlpha.BANNER_COMPACT_FILL),
                    shape = MaterialTheme.shapes.small,
                ).padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = statusToneIcon(tone),
            contentDescription = null,
            modifier = Modifier.size(IconSize.Sm.scaled()),
            tint = semantic.foreground,
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (actions != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}
