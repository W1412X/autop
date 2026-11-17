/**
 * 悬浮球圆弧菜单（docs/tech/08 §5.1，N182 / N225）：迷你球按钮沿圆弧环绕悬浮球展开（开口朝屏幕内侧），
 * 按钮与悬浮球同一套语言（主题色圆盘 + 眼白图标）。列表按钮打开运行中会话面板，面板内的小脸与悬浮球同源。
 * 点空白或选中动作收起。动效受 `ui.appearance.motionEnabled` 控制，关闭时直接落到静态终态。
 */
package com.example.autop.feature.overlay.ball

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.autop.core.common.ThreadId
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.ballface.AutopBallFace
import com.example.autop.core.ui.component.ballface.BallFacePaint
import com.example.autop.core.ui.component.ballface.BallFaceScene
import com.example.autop.core.ui.component.ballface.BallFaceSpec
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.feature.overlay.R
import com.example.autop.feature.overlay.rememberBreath
import com.example.autop.feature.overlay.rememberRotation
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 菜单按钮中心到球心的距离（球边长倍数，docs/tech/08 §5.1）；2.2 倍时相邻弦距大于按钮直径。 */
private const val MENU_RADIUS_BALLS: Float = 2.2f

/** 菜单按钮圆形直径（dp，触摸区不小于 48dp）。 */
private val MENU_BUTTON_SIZE: Dp = AutopDimens.minTouchTarget

/** 按钮图标尺寸。 */
private val MENU_ICON_SIZE: Dp = 24.dp

/** 列表按钮内小脸尺寸（最紧急会话）。 */
private val MENU_FACE_SIZE: Dp = 34.dp

/** 会话面板里的小脸尺寸。 */
private val SESSION_FACE_SIZE: Dp = 40.dp

/** 运行中角标高度。 */
private val BADGE_HEIGHT: Dp = 18.dp

/** 会话面板离屏幕顶部的距离（避开状态栏）。 */
private val SHEET_TOP: Dp = 96.dp

/** 运行中会话的轨道卫星点离球心的半径（超出按钮圆的一圈）。 */
private val ORBIT_RADIUS: Dp = 30.dp

/** 按钮依次弹出的错开时长（毫秒）。 */
private const val ENTER_STAGGER_MS: Long = 45L

/** 按下时的缩放（松开回弹）。 */
private const val PRESS_SCALE: Float = 0.86f

/** 待机浮动幅度（dp）。 */
private const val BOB_DP: Float = 2.5f

/** 待机浮动半周期基数（毫秒）；每个按钮加一个错开量，避免整排同步。 */
private const val BOB_HALF_PERIOD_MS: Long = 1800L

/** 卫星点绕行周期（毫秒）。 */
private const val ORBIT_PERIOD_MS: Long = 5000L

/** 角标超过该数字显示为 99+。 */
private const val BADGE_MAX: Int = 99

/** 待机浮动：每个按钮的半周期错开量（毫秒）。 */
private const val BOB_STEP_MS: Long = 130L

/** 弹出时的弹簧阻尼（略带回弹）。 */
private const val ENTER_DAMPING: Float = 0.55f

/** 圆弧菜单回调（由 BallMenuController 实现）。 */
internal class BallMenuCallbacks(
    val onAction: (BallMenuAction) -> Unit,
    val onOpenSession: (ThreadId) -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * 圆弧菜单层：按钮环绕球心（[ballCenterX]/[ballCenterY] 为屏幕绝对坐标 px）。
 * 列表按钮不回调控制器，而是在本层展开会话面板。
 */
@Composable
internal fun BallMenuScreen(
    actions: List<BallMenuAction>,
    sessions: List<MenuSession>,
    ballCenterX: Float,
    ballCenterY: Float,
    motionEnabled: Boolean,
    callbacks: BallMenuCallbacks,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val ballDp = LocalBallDims.current.ballDp
    val radiusPx = with(density) { (ballDp * MENU_RADIUS_BALLS).toPx() }
    val buttonPx = with(density) { MENU_BUTTON_SIZE.toPx() }
    val spots =
        arcSpots(
            actions = actions,
            ballCenterX = ballCenterX,
            ballCenterY = ballCenterY,
            radiusPx = radiusPx,
            screenWidthPx = windowSize.width.toFloat(),
            screenHeightPx = windowSize.height.toFloat(),
            buttonSizePx = buttonPx,
            itemWidthPx = buttonPx,
            itemHeightPx = buttonPx,
        )
    val motion = motionEnabled && LocalMotionEnabled.current
    var sheetOpen by remember { mutableStateOf(false) }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clickable(onClick = callbacks.onDismiss),
    ) {
        if (!sheetOpen) {
            spots.forEachIndexed { index, spot ->
                MenuButton(
                    action = spot.action,
                    index = index,
                    fromX = ballCenterX,
                    fromY = ballCenterY,
                    toX = spot.x,
                    toY = spot.y,
                    buttonPx = buttonPx,
                    sessions = sessions,
                    motion = motion,
                    onClick = {
                        if (spot.action == BallMenuAction.LIST) {
                            sheetOpen = true
                        } else {
                            callbacks.onAction(spot.action)
                        }
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = sheetOpen,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = SHEET_TOP, start = 16.dp, end = 16.dp),
            enter = if (motion) fadeIn() + scaleIn(initialScale = SHEET_INITIAL_SCALE) else EnterTransition.None,
            exit = if (motion) fadeOut() + scaleOut(targetScale = SHEET_INITIAL_SCALE) else ExitTransition.None,
        ) {
            SessionSheet(sessions = sessions, motion = motion, onOpen = callbacks.onOpenSession)
        }
    }
}

/** 会话面板展开前的缩放。 */
private const val SHEET_INITIAL_SCALE: Float = 0.6f

/** 会话面板圆角、最大宽度，行圆角。 */
private val SHEET_RADIUS: Dp = 24.dp
private val SHEET_MAX_WIDTH: Dp = 360.dp
private val ROW_RADIUS: Dp = 16.dp

/** 角标相对按钮右上角的外移量。 */
private val BADGE_OFFSET: Dp = 6.dp

/** 绕行环的透明度、线宽与绕行点半径。 */
private const val ORBIT_RING_ALPHA: Float = 0.3f
private val ORBIT_RING_WIDTH: Dp = 1.dp
private val ORBIT_DOT_RADIUS: Dp = 3.dp

/**
 * 菜单按钮。展开时从球心沿弧弹出（错开 [ENTER_STAGGER_MS]）；按下缩小、松开回弹；待机轻微浮动。
 * 动效关闭时直接落到终态，不启动任何循环动画。
 */
@Composable
private fun MenuButton(
    action: BallMenuAction,
    index: Int,
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    buttonPx: Float,
    sessions: List<MenuSession>,
    motion: Boolean,
    onClick: () -> Unit,
) {
    val enter = remember { Animatable(if (motion) 0f else 1f) }
    EnterAnimation(enter = enter, index = index, motion = motion)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = spring(dampingRatio = ENTER_DAMPING, stiffness = Spring.StiffnessMedium),
        label = "ball_menu_press",
    )
    val bob = rememberBreath(BOB_HALF_PERIOD_MS + index * BOB_STEP_MS, enabled = motion)
    val density = LocalDensity.current
    val dim = action == BallMenuAction.LIST && sessions.isEmpty()
    val buttonColor = if (dim) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
    val label = menuLabelOf(action)
    val half = buttonPx / 2
    Box(
        modifier =
            Modifier
                .graphicsLayer {
                    val t = enter.value
                    scaleX = t.coerceAtLeast(0f)
                    scaleY = t.coerceAtLeast(0f)
                    alpha = t.coerceIn(0f, 1f)
                }.offset {
                    val t = enter.value
                    IntOffset(
                        (fromX + (toX - fromX) * t - half).roundToInt(),
                        (fromY + (toY - fromY) * t - half).roundToInt(),
                    )
                },
    ) {
        Box(
            modifier =
                Modifier
                    .offset { IntOffset(0, (-BOB_DP * bob.value * density.density).roundToInt()) }
                    .size(MENU_BUTTON_SIZE)
                    .graphicsLayer {
                        scaleX = press
                        scaleY = press
                    }.semantics { contentDescription = label }
                    .background(color = buttonColor, shape = CircleShape)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ButtonGlyph(action = action, sessions = sessions, dim = dim, motion = motion)
        }
    }
}

/** 展开动画：按序号错开启动；动效关闭时直接落到终态，不启动任何循环。 */
@Composable
private fun EnterAnimation(
    enter: Animatable<Float, AnimationVector1D>,
    index: Int,
    motion: Boolean,
) {
    LaunchedEffect(Unit) {
        if (motion) {
            delay(index * ENTER_STAGGER_MS)
            enter.animateTo(1f, spring(dampingRatio = ENTER_DAMPING, stiffness = Spring.StiffnessMediumLow))
        } else {
            enter.snapTo(1f)
        }
    }
}

/** 按钮图标：列表有会话时是最紧急会话的小脸 + 绕行点 + 角标，其余为动作图标（灰显时用次要色）。 */
@Composable
private fun BoxScope.ButtonGlyph(
    action: BallMenuAction,
    sessions: List<MenuSession>,
    dim: Boolean,
    motion: Boolean,
) {
    if (action == BallMenuAction.LIST && sessions.isNotEmpty()) {
        ListFace(session = sessions.first(), motion = motion)
        OrbitDot(motion = motion)
        SessionBadge(count = sessions.size)
    } else {
        Icon(
            imageVector = menuIconOf(action),
            contentDescription = null,
            modifier = Modifier.size(MENU_ICON_SIZE),
            tint = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** 列表按钮内的小脸：最紧急会话的表情（按钮是主题色，所以圆盘用亮面）。 */
@Composable
private fun ListFace(
    session: MenuSession,
    motion: Boolean,
) {
    AutopBallFace(
        spec =
            BallFaceSpec(
                mood = session.mood,
                variant = null,
                paint = BallFacePaint.INVERSE,
                scene = BallFaceScene.SAMPLING,
                seed = session.seed,
                motionEnabled = motion,
            ),
        modifier = Modifier.size(MENU_FACE_SIZE),
    )
}

/** 列表按钮外圈的绕行点（运行中才显示，表示“在工作”）。 */
@Composable
private fun OrbitDot(motion: Boolean) {
    val angle = rememberRotation(periodMs = ORBIT_PERIOD_MS, enabled = motion)
    val color = MaterialTheme.colorScheme.primary
    val radiusPx = with(LocalDensity.current) { ORBIT_RADIUS.toPx() }
    Canvas(
        modifier =
            Modifier
                .size(MENU_BUTTON_SIZE + ORBIT_RADIUS * 2)
                .graphicsLayer { rotationZ = angle.value },
    ) {
        drawCircle(
            color = color.copy(alpha = ORBIT_RING_ALPHA),
            radius = radiusPx,
            center = center,
            style = Stroke(width = ORBIT_RING_WIDTH.toPx()),
        )
        drawCircle(color = color, radius = ORBIT_DOT_RADIUS.toPx(), center = Offset(center.x, center.y - radiusPx))
    }
}

/** 运行中会话数角标：放在按钮右上角。 */
@Composable
private fun BoxScope.SessionBadge(count: Int) {
    Box(
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = BADGE_OFFSET, y = -BADGE_OFFSET)
                .height(BADGE_HEIGHT)
                .widthIn(min = BADGE_HEIGHT)
                .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(BADGE_HEIGHT / 2))
                .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > BADGE_MAX) "$BADGE_MAX+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

/** 运行中会话面板：标题 + 数量，下面每行一个会话（小脸主题色，与悬浮球同源）。 */
@Composable
private fun SessionSheet(
    sessions: List<MenuSession>,
    motion: Boolean,
    onOpen: (ThreadId) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(max = SHEET_MAX_WIDTH)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(SHEET_RADIUS))
                .clickable(onClick = {})
                .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.ball_menu_list),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (sessions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.ball_menu_sessions_count, sessions.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.ball_menu_sessions_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        }
        sessions.forEach { session ->
            SessionRow(session = session, motion = motion, onOpen = onOpen)
        }
    }
}

/** 会话面板的一行：小脸 + 标题 + 状态，点击打开对话页。 */
@Composable
private fun SessionRow(
    session: MenuSession,
    motion: Boolean,
    onOpen: (ThreadId) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ROW_RADIUS))
                .clickable { onOpen(session.threadId) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutopBallFace(
            spec =
                BallFaceSpec(
                    mood = session.mood,
                    variant = null,
                    paint = BallFacePaint.THEME,
                    scene = BallFaceScene.SAMPLING,
                    seed = session.seed,
                    motionEnabled = motion,
                ),
            modifier = Modifier.size(SESSION_FACE_SIZE),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ball_menu_session_untitled),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(sessionStatusTextOf(session.status)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 动作 → 图标。 */
@Suppress("DEPRECATION") // 弃用项 VIEW_PROGRESS/ABORT_RUN 保留在枚举（代码保留口径，N218）
private fun menuIconOf(action: BallMenuAction): ImageVector =
    when (action) {
        BallMenuAction.LIST -> AutopIcons.ListAlt
        BallMenuAction.CREATE_TASK -> AutopIcons.Add
        BallMenuAction.OPEN_APP -> AutopIcons.Launch
        BallMenuAction.VIEW_PROGRESS -> AutopIcons.Visibility
        BallMenuAction.ABORT_RUN -> AutopIcons.Stop
        BallMenuAction.RECORD -> AutopIcons.Record
        BallMenuAction.TOOL_TEST -> AutopIcons.Settings
    }

/** 动作 → 无障碍描述文案（视觉无文字，读屏仍可辨）。 */
@Composable
@Suppress("DEPRECATION") // 弃用项 VIEW_PROGRESS/ABORT_RUN 保留在枚举（代码保留口径，N218）
private fun menuLabelOf(action: BallMenuAction): String =
    stringResource(
        when (action) {
            BallMenuAction.LIST -> R.string.ball_menu_list
            BallMenuAction.CREATE_TASK -> R.string.ball_menu_create
            BallMenuAction.OPEN_APP -> R.string.ball_menu_open_app
            BallMenuAction.VIEW_PROGRESS -> R.string.ball_menu_progress
            BallMenuAction.ABORT_RUN -> R.string.ball_menu_abort
            BallMenuAction.RECORD -> R.string.ball_menu_record
            BallMenuAction.TOOL_TEST -> R.string.ball_menu_tool_test
        },
    )

/** 会话状态 → 面板文案。 */
private fun sessionStatusTextOf(status: MenuSessionStatus): Int =
    when (status) {
        MenuSessionStatus.WAITING -> R.string.ball_menu_session_waiting
        MenuSessionStatus.BLOCKED -> R.string.ball_menu_session_blocked
        MenuSessionStatus.PAUSED -> R.string.ball_menu_session_paused
        MenuSessionStatus.RUNNING -> R.string.ball_menu_session_running
    }
