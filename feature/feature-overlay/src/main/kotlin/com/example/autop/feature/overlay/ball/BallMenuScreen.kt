/**
 * 悬浮球圆弧菜单（docs/tech/08 §5.1，N182）：点球后按钮沿圆弧环绕球展开（开口朝屏幕内侧），
 * 点空白或选中动作即收起。MENU 窗口整屏透明，按钮为纯图标圆钮（N22x：文字标签曾与相邻
 * 按钮重叠，改为图标钮＋无障碍描述，视觉干净）。
 */
package com.example.autop.feature.overlay.ball

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.feature.overlay.R
import kotlin.math.roundToInt

/**
 * 菜单按钮中心到球心的距离（球边长倍数，docs/tech/08 §5.1）。
 * 相邻按钮弦距 = 2R·sin(步距/2)；步距由 [arcSpots] 按 30° 保底给出，
 * 2.2 球半径时弦距 ≈ 59dp > 48dp 钮，留有余量且构图紧凑。
 */
private const val MENU_RADIUS_BALLS: Float = 2.2f

/** 菜单按钮圆形直径（dp）。 */
private val MENU_BUTTON_SIZE: Dp = AutopDimens.minTouchTarget

/** 按钮图标尺寸（图标钮比带文字时放大一号）。 */
private val MENU_ICON_SIZE: Dp = 28.dp

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
    val spots =
        arcSpots(
            actions = actions,
            ballCenterX = ballCenterX,
            ballCenterY = ballCenterY,
            radiusPx = radiusPx,
            screenWidthPx = windowSize.width.toFloat(),
            screenHeightPx = windowSize.height.toFloat(),
            buttonSizePx = buttonSizePx,
            itemWidthPx = buttonSizePx,
            itemHeightPx = buttonSizePx,
        )
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
                modifier =
                    Modifier.offset {
                        IntOffset(
                            (spot.x - circleHalf).roundToInt(),
                            (spot.y - circleHalf).roundToInt(),
                        )
                    },
            )
        }
    }
}

/** 菜单按钮：纯图标圆钮（无文字防重叠）；「中止任务」用 error 色（危险动作，docs/tech/08 §6）。 */
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
    val label = menuLabelOf(action)
    Box(
        modifier =
            modifier
                .size(MENU_BUTTON_SIZE)
                .semantics { contentDescription = label }
                .background(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = menuIconOf(action),
            contentDescription = null,
            modifier = Modifier.size(MENU_ICON_SIZE),
            tint = tint,
        )
    }
}

/** 动作 → 图标。 */
@Suppress("DEPRECATION") // 弃用项 VIEW_PROGRESS/ABORT_RUN 保留在枚举（代码保留口径，N218）
private fun menuIconOf(action: BallMenuAction): ImageVector =
    when (action) {
        BallMenuAction.CREATE_TASK -> AutopIcons.Add
        BallMenuAction.OPEN_APP -> AutopIcons.Launch
        BallMenuAction.VIEW_PROGRESS -> AutopIcons.Visibility
        BallMenuAction.ABORT_RUN -> AutopIcons.Stop
        BallMenuAction.RECORD -> AutopIcons.Record
        BallMenuAction.TOOL_TEST -> AutopIcons.Settings
        BallMenuAction.HIDE_BALL -> AutopIcons.VisibilityOff
    }

/** 动作 → 无障碍描述文案（视觉无文字，读屏仍可辨）。 */
@Composable
@Suppress("DEPRECATION") // 弃用项 VIEW_PROGRESS/ABORT_RUN 保留在枚举（代码保留口径，N218）
private fun menuLabelOf(action: BallMenuAction): String =
    stringResource(
        when (action) {
            BallMenuAction.CREATE_TASK -> R.string.ball_menu_create
            BallMenuAction.OPEN_APP -> R.string.ball_menu_open_app
            BallMenuAction.VIEW_PROGRESS -> R.string.ball_menu_progress
            BallMenuAction.ABORT_RUN -> R.string.ball_menu_abort
            BallMenuAction.RECORD -> R.string.ball_menu_record
            BallMenuAction.TOOL_TEST -> R.string.ball_menu_tool_test
            BallMenuAction.HIDE_BALL -> R.string.ball_menu_hide
        },
    )
