/**
 * 运行详情面板（点控制条操作信息展开的观测视图）：只显示智能体消息流（进度悬浮窗同款
 * 组件，[ProgressMessageList]），无头部/控制/输入，纯观测；再点操作信息收起。
 */
package com.example.autop.feature.overlay.indicator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.overlay.progress.ProgressMessageList
import com.example.autop.feature.overlay.progress.ProgressRow

/** 运行详情面板内容（窗口高度由 spec 定，内容铺满窗口）。 */
@Composable
internal fun RunDetailScreen(
    rows: List<ProgressRow>,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ).padding(all = spacing.sm),
    ) {
        ProgressMessageList(rows = rows, modifier = Modifier.fillMaxSize())
    }
}
