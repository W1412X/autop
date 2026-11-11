/**
 * 顶栏组件（docs/tech/08 §2）：[SubPageTopBar] 手写子页顶栏（不用 Scaffold/TopAppBar）；
 * [TopLevelHeader] 顶级页大标题。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalSpacing

/** 子页顶栏：4×4 内边距、44dp 返回按钮 + titleLarge 标题 + 右侧动作。 */
@Composable
public fun SubPageTopBar(
    title: String,
    titleIcon: ImageVector? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(all = LocalSpacing.current.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutopIconButton(
            icon = AutopIcons.Back,
            contentDescription = stringResource(R.string.core_ui_back),
            onClick = onBack,
        )
        titleIcon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = LocalSpacing.current.sm),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = LocalSpacing.current.sm),
        )
        actions?.invoke(this)
    }
}

/** 顶级页大标题：start 24 / end 12 / top 32 / bottom 20 内边距，headlineSmall。 */
@Composable
public fun TopLevelHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = AutopDimens.headerPaddingStart,
                    end = AutopDimens.headerPaddingEnd,
                    top = AutopDimens.headerPaddingTop,
                    bottom = AutopDimens.headerPaddingBottom,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions?.invoke(this)
    }
}
