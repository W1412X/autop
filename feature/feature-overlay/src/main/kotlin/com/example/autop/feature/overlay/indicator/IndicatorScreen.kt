/**
 * 执行期指示的界面（ADR-0014）：沿屏幕四周的柔光带，颜色取主题色（主色、第三色、次色循环）并缓慢流动，
 * 光带随呼吸起伏并轻微涨落；顶部胶囊显示步数。颜色全部取自 [MaterialTheme.colorScheme]，
 * 随主题色与深浅色动态变化。
 *
 * 光带形状来自一次性生成的高斯模糊遮罩（[buildGlowMask]），渐变连续、没有分层；
 * 每帧只用旋转的扫描渐变上色（`SrcIn`）。随帧变化的值都只在绘制阶段读取，整屏界面不随动画重组。
 * 出现与消失有淡入淡出；动效关闭（[LocalMotionEnabled]）时光带静止、无过渡。
 */
package com.example.autop.feature.overlay.indicator

import android.graphics.Matrix
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.overlay.R
import com.example.autop.feature.overlay.rememberBreath
import com.example.autop.feature.overlay.rememberRotation
import kotlin.math.roundToInt

/** 淡入时长（毫秒）。 */
private const val FADE_IN_MS: Int = 450

/** 淡出时长（毫秒）；控制器在淡出结束后才移除窗口。 */
internal const val INDICATOR_FADE_OUT_MS: Long = 350L

/** 光带最暗 / 最亮时的整体不透明度。 */
private const val GLOW_ALPHA_MIN: Float = 0.45f
private const val GLOW_ALPHA_MAX: Float = 1f

/** 光向屏内延伸的深度，取 spacing.xl 的倍数。 */
private const val GLOW_DEPTH_FACTOR: Int = 3

/** 光带圆角，取 spacing.xl 的倍数（贴合屏幕圆角）。 */
private const val GLOW_CORNER_FACTOR: Int = 2

/**
 * 呼吸时光带的涨落：最暗时整体放大到该倍数（光带向屏外退去），最亮时回到 1（光涌入屏内）。
 * 缩放围绕屏幕中心，超出窗口的部分被裁掉。
 */
private const val GLOW_SCALE_MAX: Float = 1.05f

/** 流光绕屏一周的时长（毫秒）。 */
private const val GLOW_ROTATE_MS: Long = 9000L

/** 胶囊状态点随呼吸缩放的下限。 */
private const val DOT_SCALE_MIN: Float = 0.7f

/** 胶囊状态点的直径倍数（相对 spacing.sm）与顶部留白倍数（相对 spacing.xl）。 */
private const val DOT_SIZE_FACTOR: Int = 2
private const val CAPSULE_TOP_FACTOR: Int = 2

/** 胶囊淡入时从上方滑入的距离，取 spacing.md 的倍数。 */
private const val CAPSULE_SLIDE_FACTOR: Int = 2

/** 执行期指示内容。[visible] 为 false 时淡出（窗口由控制器在淡出后移除）。 */
@Composable
internal fun IndicatorScreen(
    stepCount: Int,
    edgeGlow: Boolean,
    capsule: Boolean,
    pulseMs: Long,
    visible: Boolean = true,
) {
    val motionOn = LocalMotionEnabled.current
    val breath = rememberBreath(halfPeriodMs = pulseMs / 2, enabled = motionOn)
    val rotation = rememberRotation(periodMs = GLOW_ROTATE_MS, enabled = motionOn)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(visible, motionOn) {
        val target = if (visible) 1f else 0f
        if (motionOn) {
            val duration = if (visible) FADE_IN_MS else INDICATOR_FADE_OUT_MS.toInt()
            appear.animateTo(target, tween(durationMillis = duration))
        } else {
            appear.snapTo(target)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (edgeGlow) EdgeGlow(breath = breath, rotation = rotation, appear = appear)
        if (capsule) Capsule(stepCount = stepCount, appear = appear, breath = breath, motionOn = motionOn)
    }
}

/**
 * 边缘光带：整层离屏合成，先画模糊遮罩，再用旋转扫描渐变以 `SrcIn` 上色，
 * 所以颜色只出现在光带内，边缘羽化保持连续。
 */
@Composable
private fun EdgeGlow(
    breath: State<Float>,
    rotation: State<Float>,
    appear: Animatable<Float, AnimationVector1D>,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = LocalSpacing.current
    val depth = spacing.xl * GLOW_DEPTH_FACTOR
    val corner = spacing.xl * GLOW_CORNER_FACTOR
    val glowColors = remember(colors.primary, colors.tertiary, colors.secondary) { glowColorsOf(colors) }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = breath.value
                    alpha = (GLOW_ALPHA_MIN + (GLOW_ALPHA_MAX - GLOW_ALPHA_MIN) * p) * appear.value
                    val scale = GLOW_SCALE_MAX - (GLOW_SCALE_MAX - 1f) * p
                    scaleX = scale
                    scaleY = scale
                    compositingStrategy = CompositingStrategy.Offscreen
                }.drawWithCache {
                    val mask = buildGlowMask(size, depth.toPx(), corner.toPx())
                    onDrawBehind { drawGlow(mask, glowColors, rotation.value) }
                },
    )
}

/** 流光色环：主色 → 第三色 → 次色 → 回到主色（首尾同色，旋转时无接缝）。 */
private fun glowColorsOf(colors: ColorScheme): List<Color> =
    listOf(colors.primary, colors.tertiary, colors.secondary, colors.primary)

/** 把遮罩拉伸到整屏，再用旋转的扫描渐变给遮罩内的像素上色。 */
private fun DrawScope.drawGlow(
    mask: androidx.compose.ui.graphics.ImageBitmap,
    colors: List<Color>,
    rotation: Float,
) {
    drawImage(
        image = mask,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        filterQuality = FilterQuality.High,
    )
    drawRect(brush = RotatingSweepBrush(colors, rotation), blendMode = BlendMode.SrcIn)
}

/** 绕屏幕中心旋转的扫描渐变画刷；每帧新建，只在绘制阶段使用。 */
private class RotatingSweepBrush(
    private val colors: List<Color>,
    private val degrees: Float,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val center = Offset(size.width / 2f, size.height / 2f)
        val shader = SweepGradientShader(center, colors)
        shader.setLocalMatrix(Matrix().apply { setRotate(degrees, center.x, center.y) })
        return shader
    }
}

/** 顶部胶囊：状态点随呼吸起伏，步数变化交叉淡入，随整体淡入从上方滑入。 */
@Composable
private fun Capsule(
    stepCount: Int,
    appear: Animatable<Float, AnimationVector1D>,
    breath: State<Float>,
    motionOn: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = LocalSpacing.current
    val slide = spacing.md * CAPSULE_SLIDE_FACTOR
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = spacing.xl * CAPSULE_TOP_FACTOR)
                    .graphicsLayer {
                        alpha = appear.value
                        translationY = -(1f - appear.value) * slide.toPx()
                    }.clip(CircleShape)
                    .background(colors.primaryContainer)
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(spacing.sm * DOT_SIZE_FACTOR)
                        .graphicsLayer {
                            val scale = DOT_SCALE_MIN + (1f - DOT_SCALE_MIN) * breath.value
                            scaleX = scale
                            scaleY = scale
                        }.clip(CircleShape)
                        .background(colors.primary),
            )
            Crossfade(
                targetState = stepCount,
                animationSpec = tween(durationMillis = if (motionOn) FADE_IN_MS / 2 else 0),
                label = "indicator_step",
            ) { step ->
                Text(
                    text = stringResource(R.string.indicator_operating, step),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onPrimaryContainer,
                    modifier = Modifier.padding(start = spacing.sm),
                )
            }
        }
    }
}
