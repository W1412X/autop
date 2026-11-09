/**
 * 顶部运行控制条（原执行期胶囊升级）：一行横排——呼吸状态点 + 操作信息（点按展开/收起
 * 下方运行详情面板）+ 状态图标按钮组。颜色取 [MaterialTheme.colorScheme]，动效遵守
 * [LocalMotionEnabled]：出入淡入淡出并从上滑入、图标交叉过渡、按压回弹、箭头旋转。
 * 窗口只包住条本身（可触摸但不吞条外触摸）。
 */
package com.example.autop.feature.overlay.indicator

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.scaled
import com.example.autop.feature.overlay.R
import com.example.autop.feature.overlay.rememberBreath
import com.example.autop.task.ActiveRunInfo
import kotlin.random.Random

/** 图标按钮与状态点尺寸（dp）。 */
private val BUTTON_SIZE = 36.dp
private val DOT_SIZE = 8.dp
private val INFO_GAP = 8.dp
private val BUTTON_GAP = 6.dp

/** 入场从上方滑入的距离（dp）。 */
private val SLIDE_DISTANCE = 12.dp

/** 状态点呼吸缩放下限。 */
private const val DOT_SCALE_MIN: Float = 0.7f

/** 状态点呼吸半周期（毫秒）。 */
private const val BREATH_HALF_MS: Long = 700L

/** 按压回弹的缩放下限。 */
private const val PRESSED_SCALE: Float = 0.88f

/** 入场 / 图标交叉过渡 / 箭头过渡时长（毫秒）。 */
private const val BAR_APPEAR_MS: Int = 250
private const val ICON_SWAP_MS: Int = 180
private const val CHEVRON_MS: Int = 200

/** 淡出时长（毫秒）；控制器在淡出结束后才移除窗口。 */
internal const val BAR_FADE_OUT_MS: Long = 250L

/** 运行控制条内容（窗口高度按 [RUN_BAR_HEIGHT] 定，内容须恰好铺满）。 */
@Composable
internal fun RunBarScreen(
    active: ActiveRunInfo,
    expanded: Boolean,
    visible: Boolean,
    onToggleExpand: () -> Unit,
    onAction: (RunBarAction) -> Unit,
) {
    // 变体文案：按状态/步数/工具变化重抽（同段展示稳定），上一次说法降权（hintTitleOf）。
    val lastHint = remember { mutableStateOf<String?>(null) }
    val random = remember { Random.Default }
    val spec =
        remember(active.run.status, active.state, active.stepCount, active.toolId, active.toolTarget, active.flow) {
            runBarSpecOf(active, previousHint = lastHint.value, roll = random.nextFloat()).also { next ->
                lastHint.value = (next.hint as? RunBarHint.Running)?.title
            }
        }
    val motionOn = LocalMotionEnabled.current
    val breath = rememberBreath(halfPeriodMs = BREATH_HALF_MS, enabled = motionOn && spec.hint is RunBarHint.Running)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(visible, motionOn) {
        val target = if (visible) 1f else 0f
        if (motionOn) {
            val duration = if (visible) BAR_APPEAR_MS else BAR_FADE_OUT_MS.toInt()
            appear.animateTo(target, tween(durationMillis = duration))
        } else {
            appear.snapTo(target)
        }
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(RUN_BAR_HEIGHT)
                .graphicsLayer {
                    alpha = appear.value
                    translationY = -(1f - appear.value) * SLIDE_DISTANCE.toPx()
                }.background(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape)
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
            key(buttonSlotOf(action)) {
                RunBarActionButton(
                    action = action,
                    onClick = { onAction(action) },
                    modifier = Modifier.padding(start = BUTTON_GAP),
                )
            }
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
                .size(IconSize.Xs.scaled())
                .graphicsLayer { rotationZ = rotation },
    )
}

/**
 * 状态按钮：圆形图标按钮（暂停/继续、中止），按压回弹 + 涟漪反馈；
 * 暂停/继续同一槽位（[buttonSlotOf]）复用节点，图标交叉过渡。
 */
@Composable
private fun RunBarActionButton(
    action: RunBarAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionOn = LocalMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = if (motionOn) spring(dampingRatio = Spring.DampingRatioMediumBouncy) else snap(),
        label = "run_bar_press",
    )
    val colors = MaterialTheme.colorScheme
    val stop = action == RunBarAction.STOP
    val container = if (stop) colors.errorContainer else colors.primary
    val content = if (stop) colors.onErrorContainer else colors.onPrimary
    Box(
        modifier =
            modifier
                .size(BUTTON_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(CircleShape)
                .background(container)
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = action,
            animationSpec = tween(durationMillis = if (motionOn) ICON_SWAP_MS else 0),
            label = "run_bar_icon",
        ) { shown ->
            Icon(
                imageVector = iconOf(shown),
                contentDescription = buttonLabelOf(shown),
                tint = content,
                modifier = Modifier.size(IconSize.Sm.scaled()),
            )
        }
    }
}

/** 按钮槽位：暂停/继续共用 0（状态切换时原地交叉过渡），中止恒为 1。 */
private fun buttonSlotOf(action: RunBarAction): Int = if (action == RunBarAction.STOP) 1 else 0

/** 按钮图标。 */
private fun iconOf(action: RunBarAction): ImageVector =
    when (action) {
        RunBarAction.PAUSE -> AutopIcons.Pause
        RunBarAction.RESUME -> AutopIcons.Play
        RunBarAction.STOP -> AutopIcons.Stop
    }

/** 状态文案（运行中为变体标题 + 「第 N 步」，见 [hintTitleOf]）。 */
@Composable
private fun hintTextOf(hint: RunBarHint): String =
    when {
        hint is RunBarHint.Running && hint.stepCount > 0 ->
            stringResource(R.string.run_bar_running, hint.title, hint.stepCount)
        hint is RunBarHint.Running -> hint.title
        hint == RunBarHint.Paused -> stringResource(R.string.panel_status_paused)
        hint == RunBarHint.Blocked -> stringResource(R.string.panel_status_blocked)
        hint == RunBarHint.Waiting -> stringResource(R.string.panel_status_waiting)
        else -> stringResource(R.string.panel_status_queued)
    }

/** 按钮无障碍文案（视觉为图标，读屏读文字）。 */
@Composable
private fun buttonLabelOf(action: RunBarAction): String =
    when (action) {
        RunBarAction.PAUSE -> stringResource(R.string.panel_action_pause)
        RunBarAction.RESUME -> stringResource(R.string.panel_action_resume)
        RunBarAction.STOP -> stringResource(R.string.run_bar_stop)
    }
