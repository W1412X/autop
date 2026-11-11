/**
 * 面板容器（docs/tech/08 §5.2，N182）：surfaceContainerHigh 底、medium 圆角、md 内边距。
 * 运行/问题/拦截面板迁入进度悬浮窗（N182），此处只承载输入面板。
 */
package com.example.autop.feature.overlay.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.theme.LocalSpacing

/** 面板内容：输入面板（即时创建任务）。 */
@Composable
internal fun PanelScreen(
    callbacks: PanelCallbacks,
    modelOptions: List<OptionItem> = emptyList(),
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
                ).padding(all = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        InputPanel(callbacks = callbacks, modelOptions = modelOptions)
    }
}
