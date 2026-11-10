/**
 * 运行详情面板（点控制条操作信息展开的消息条）：智能体消息流（进度悬浮窗同款组件，
 * [ProgressMessageList]）+ 执行中交互区（[RunInteractArea]：提问卡/拦截横幅/补充指令），
 * 执行中需要用户交互就地完成，零跳转；再点操作信息收起。
 */
package com.example.autop.feature.overlay.indicator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.overlay.R
import com.example.autop.feature.overlay.panel.ControlIntent
import com.example.autop.feature.overlay.progress.AlertCards
import com.example.autop.feature.overlay.progress.ProgressCallbacks
import com.example.autop.feature.overlay.progress.ProgressMessageList
import com.example.autop.feature.overlay.progress.ProgressRow
import com.example.autop.feature.overlay.progress.SteerRow
import com.example.autop.task.ActiveRunInfo

/** 运行详情面板内容（窗口高度由 spec 定，内容铺满窗口）。 */
@Composable
internal fun RunDetailScreen(
    active: ActiveRunInfo?,
    rows: List<ProgressRow>,
    callbacks: ProgressCallbacks,
    intent: ControlIntent? = null,
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
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        ProgressMessageList(rows = rows, modifier = Modifier.fillMaxWidth().weight(1f))
        RunInteractArea(active = active, callbacks = callbacks, intent = intent)
    }
}

/**
 * 执行中交互区：等待回答/拦截/暂停时给就地操作卡（[AlertCards]），
 * 其余运行中给补充指令输入（[SteerRow]）；流程执行中附「只停流程」。
 */
@Composable
internal fun RunInteractArea(
    active: ActiveRunInfo?,
    callbacks: ProgressCallbacks,
    intent: ControlIntent? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        AlertCards(active = active, callbacks = callbacks, intent = intent)
        if (active != null && active.run.waitingQuestion == null) {
            SteerRow(callbacks = callbacks)
        }
        if (active?.flow != null) {
            AutopTonalButton(
                text = stringResource(R.string.panel_action_stop_flow),
                onClick = callbacks.onStopFlow,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
