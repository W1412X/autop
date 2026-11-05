/**
 * 悬浮球圆弧菜单（docs/tech/08 §5.1，N182）：点球后按钮沿圆弧环绕球展开（开口朝屏幕内侧），
 * 点空白或选中动作即收起。MENU 窗口整屏透明，按钮为「圆形图标 + 下方文字」。
 */
package com.example.autop.feature.overlay.ball

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled
import com.example.autop.feature.overlay.R
import kotlin.math.roundToInt

/**
 * 菜单按钮中心到球心的距离（球边长倍数，docs/tech/08 §5.1）。
 * N217：弧面相邻按钮中心间距 = R·sin35°，须大于整块按钮高（圆 + 文字），
 * 否则上一个按钮的文字被下一个按钮的圆压住；1.8 不够（53dp < 76dp），调到 2.6。
 */
private const val MENU_RADIUS_BALLS: Float = 2.6f

/** 菜单按钮圆形直径（dp）。 */
private val MENU_BUTTON_SIZE: Dp = AutopDimens.minTouchTarget

/** 整块按钮宽（圆 + 文字外接盒，dp；[MenuButton] 定宽，超宽文字省略）。 */
private val MENU_ITEM_WIDTH: Dp = 76.dp

/** 文字行高上限（dp，含放大字号余量）：弧面间距与贴边钳制用。 */
private val MENU_LABEL_HEIGHT: Dp = 24.dp

/** 圆弧菜单动作回调（由 BallMenuController 实现）。 */
internal class BallMenuCallbacks(
    val onAction: (BallMenuAction) -> Unit,
    val onDismiss: () -> Unit,
)

/** 圆弧菜单层：按钮环绕球心（[ballCenterX]/[ballCenterY] 为屏幕绝对坐标 px）。 */
@Composable
internal fun BallMenuScreen(
    actions: List<BallMenuAction>,
    ballCenterX: Float,
    ballCenterY: Float,
    callbacks: BallMenuCallbacks,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val radiusPx = with(density) { (BALL_SIZE * MENU_RADIUS_BALLS).toPx() }
    val buttonSizePx = with(density) { MENU_BUTTON_SIZE.toPx() }
    val itemWidthPx = with(density) { MENU_ITEM_WIDTH.toPx() }
    val itemHeightPx = with(density) { (MENU_BUTTON_SIZE + LocalSpacing.current.xs + MENU_LABEL_HEIGHT).toPx() }
    val spots =
        arcSpots(
            actions = actions,
            ballCenterX = ballCenterX,
            ballCenterY = ballCenterY,
            radiusPx = radiusPx,
            screenWidthPx = windowSize.width.toFloat(),
            screenHeightPx = windowSize.height.toFloat(),
            buttonSizePx = buttonSizePx,
            itemWidthPx = itemWidthPx,
            itemHeightPx = itemHeightPx,
        )
    val halfWidth = itemWidthPx / 2
    val circleHalf = buttonSizePx / 2
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clickable(onClick = callbacks.onDismiss),
    ) {
        spots.forEach { spot ->
            MenuButton(
                action = spot.action,
                onClick = { callbacks.onAction(spot.action) },
                // 圆心落在弧线上（N217）：定宽整块按钮，左上角 = 圆心 - (半宽, 圆半径)。
                modifier =
                    Modifier.offset {
                        IntOffset(
                            (spot.x - halfWidth).roundToInt(),
                            (spot.y - circleHalf).roundToInt(),
                        )
                    },
            )
        }
    }
}

/** 菜单按钮：圆形图标 + 下方文字；「中止任务」用 error 色（危险动作，docs/tech/08 §6）。 */
@Composable
private fun MenuButton(
    action: BallMenuAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val destructive = action == BallMenuAction.ABORT_RUN
    val tint =
        if (destructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Column(
        modifier = modifier.width(MENU_ITEM_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        Box(
            modifier =
                Modifier
                    .size(MENU_BUTTON_SIZE)
                    .background(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape)
                    .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = menuIconOf(action),
                contentDescription = null,
                modifier = Modifier.size(IconSize.Md.scaled()),
                tint = tint,
            )
        }
        Text(
            text = menuLabelOf(action),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 动作 → 图标。 */
private fun menuIconOf(action: BallMenuAction): ImageVector =
    when (action) {
        BallMenuAction.CREATE_TASK -> AutopIcons.Add
        BallMenuAction.OPEN_APP -> AutopIcons.Launch
        BallMenuAction.VIEW_PROGRESS -> AutopIcons.Visibility
        BallMenuAction.ABORT_RUN -> AutopIcons.Stop
        BallMenuAction.TOOL_TEST -> AutopIcons.Settings
    }

/** 动作 → 文案。 */
@Composable
private fun menuLabelOf(action: BallMenuAction): String =
    stringResource(
        when (action) {
            BallMenuAction.CREATE_TASK -> R.string.ball_menu_create
            BallMenuAction.OPEN_APP -> R.string.ball_menu_open_app
            BallMenuAction.VIEW_PROGRESS -> R.string.ball_menu_progress
            BallMenuAction.ABORT_RUN -> R.string.ball_menu_abort
            BallMenuAction.TOOL_TEST -> R.string.ball_menu_tool_test
        },
    )
