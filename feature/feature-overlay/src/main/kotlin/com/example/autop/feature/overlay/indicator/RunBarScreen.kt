/**
 * 顶部运行控制条（原执行期胶囊升级）：一行横排——呼吸状态点 + 操作信息（点按展开/收起
 * 下方运行详情面板）+ 状态按钮组。颜色取 [MaterialTheme.colorScheme]，动效遵守
 * [LocalMotionEnabled]；窗口只包住条本身（可触摸但不吞条外触摸）。
 */
package com.example.autop.feature.overlay.indicator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.feature.overlay.R
import com.example.autop.feature.overlay.rememberBreath
import com.example.autop.task.ActiveRunInfo

/** 按钮与状态点尺寸（dp）；条高取 [RUN_BAR_HEIGHT]（与窗口 spec 同源）。 */
private val BUTTON_HEIGHT = 30.dp
private val BUTTON_PADDING_H = 12.dp
private val DOT_SIZE = 8.dp
private val INFO_GAP = 8.dp
private val BUTTON_GAP = 6.dp

/** 状态点呼吸缩放下限。 */
private const val DOT_SCALE_MIN: Float = 0.7f

/** 状态点呼吸半周期（毫秒）。 */
private const val BREATH_HALF_MS: Long = 700L

/** 展开箭头过渡时长（毫秒）。 */
private const val CHEVRON_MS: Int = 200

/** 运行控制条内容（窗口高度按 [RUN_BAR_HEIGHT] 定，内容须恰好铺满）。 */
@Composable
internal fun RunBarScreen(
    active: ActiveRunInfo,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onAction: (RunBarAction) -> Unit,
) {
    val spec = runBarSpecOf(active)
    val motionOn = LocalMotionEnabled.current
    val breath = rememberBreath(halfPeriodMs = BREATH_HALF_MS, enabled = motionOn && spec.hint is RunBarHint.Running)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(RUN_BAR_HEIGHT)
                .background(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape)
                .padding(horizontal = INFO_GAP * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(hint = spec.hint, breath = breath)
        InfoArea(
            expanded = expanded,
            text = hintTextOf(spec.hint),
            onToggleExpand = onToggleExpand,
            modifier = Modifier.weight(1f),
        )
        spec.buttons.forEach { action ->
            RunBarActionButton(
                action = action,
                onClick = { onAction(action) },
                modifier = Modifier.padding(start = BUTTON_GAP),
            )
        }
    }
}

/** 状态点：运行中随呼吸起伏，其余静止（色即状态语义）。 */
@Composable
private fun StatusDot(
    hint: RunBarHint,
    breath: State<Float>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val color =
        when (hint) {
            is RunBarHint.Running -> colors.primary
            RunBarHint.Paused -> colors.tertiary
            RunBarHint.Blocked -> colors.error
            RunBarHint.Waiting -> colors.secondary
            RunBarHint.Queued -> colors.outline
        }
    Box(
        modifier =
            modifier
                .size(DOT_SIZE)
                .graphicsLayer {
                    val scale = DOT_SCALE_MIN + (1f - DOT_SCALE_MIN) * breath.value
                    scaleX = scale
                    scaleY = scale
                }.clip(CircleShape)
                .background(color),
    )
}

/** 操作信息区（点按展开/收起详情）：状态文案单行截断 + 展开箭头。 */
@Composable
private fun InfoArea(
    expanded: Boolean,
    text: String,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxHeight()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = INFO_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ExpandChevron(expanded = expanded)
    }
}

/** 展开箭头：收起朝下、展开朝上，随动效旋转。 */
@Composable
private fun ExpandChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val motionOn = LocalMotionEnabled.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 270f else 90f,
        animationSpec = tween(durationMillis = if (motionOn) CHEVRON_MS else 0),
        label = "run_bar_chevron",
    )
    Icon(
        imageVector = AutopIcons.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier =
            modifier
                .size(INFO_GAP * 2)
                .graphicsLayer { rotationZ = rotation },
    )
}

/** 状态按钮：暂停/继续主色胶囊，中止错误色胶囊（破坏性动作醒目）。 */
@Composable
private fun RunBarActionButton(
    action: RunBarAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val stop = action == RunBarAction.STOP
    val container = if (stop) colors.errorContainer else colors.primary
    val content = if (stop) colors.onErrorContainer else colors.onPrimary
    Box(
        modifier =
            modifier
                .height(BUTTON_HEIGHT)
                .clip(CircleShape)
                .background(container)
                .clickable(onClick = onClick)
                .padding(horizontal = BUTTON_PADDING_H),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = buttonTextOf(action),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
        )
    }
}

/** 状态文案（运行中为「操作 · 第 N 步」，标题缺省退化为纯步数/状态词）。 */
@Composable
private fun hintTextOf(hint: RunBarHint): String =
    when {
        hint is RunBarHint.Running && hint.title != null && hint.stepCount > 0 ->
            stringResource(R.string.run_bar_running, hint.title, hint.stepCount)
        hint is RunBarHint.Running && hint.title != null -> hint.title
        hint is RunBarHint.Running && hint.stepCount > 0 -> stringResource(R.string.run_bar_step, hint.stepCount)
        hint is RunBarHint.Running -> stringResource(R.string.panel_status_running)
        hint == RunBarHint.Paused -> stringResource(R.string.panel_status_paused)
        hint == RunBarHint.Blocked -> stringResource(R.string.panel_status_blocked)
        hint == RunBarHint.Waiting -> stringResource(R.string.panel_status_waiting)
        else -> stringResource(R.string.panel_status_queued)
    }

/** 按钮文案。 */
@Composable
private fun buttonTextOf(action: RunBarAction): String =
    when (action) {
        RunBarAction.PAUSE -> stringResource(R.string.panel_action_pause)
        RunBarAction.RESUME -> stringResource(R.string.panel_action_resume)
        RunBarAction.STOP -> stringResource(R.string.run_bar_stop)
    }
