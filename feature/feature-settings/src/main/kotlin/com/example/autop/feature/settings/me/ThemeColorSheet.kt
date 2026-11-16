/**
 * 自定义主题色调色盘（docs/tech/08 §1.1）：色相 / 饱和度 / 明度三条渐变取色条 +
 * 实时 M3 色板预览。选中的种子色经 `SeedPalette` 推导为整套色板，保证文字对比度。
 */
package com.example.autop.feature.settings.me

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.core.config.UiAppearanceSection
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.DarkMode
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.SeedColors
import com.example.autop.core.ui.theme.SeedPalette
import com.example.autop.core.ui.theme.toHsl
import com.example.autop.core.ui.theme.toSeedArgb
import com.example.autop.feature.settings.R
import kotlin.math.roundToInt

/** 调色盘取色弹层：应用后写入 `ui.appearance.customSeed`。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeColorSheet(
    appearance: UiAppearanceSection,
    onApply: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val darkTheme =
        when (appearance.darkMode) {
            DarkMode.DARK.name -> true
            DarkMode.LIGHT.name -> false
            else -> isSystemInDarkTheme()
        }
    // 旧版本档位可能超出当前预设数量，夹取后再查表（SeedColors 越界会抛异常）。
    val seedIndex = appearance.seedIndex.coerceIn(0, SeedColors.COUNT - 1)
    val currentScheme =
        if (darkTheme) SeedColors.dark(seedIndex) else SeedColors.light(seedIndex)
    val initialSeed = appearance.customSeed.takeIf { it != 0L } ?: currentScheme.primary.toSeedArgb()
    val initial = remember(initialSeed) { Color(initialSeed).toHsl() }
    var hue by remember(initial) { mutableFloatStateOf(initial.hue) }
    var saturation by remember(initial) { mutableFloatStateOf(initial.saturation) }
    var lightness by remember(initial) { mutableFloatStateOf(initial.lightness) }
    val seed = Color.hsl(hue, saturation, lightness)
    val scheme =
        if (darkTheme) SeedPalette.dark(seed.toSeedArgb()) else SeedPalette.light(seed.toSeedArgb())
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        PickerContent(
            hue = hue,
            saturation = saturation,
            lightness = lightness,
            scheme = scheme,
            onHueChange = { hue = it },
            onSaturationChange = { saturation = it },
            onLightnessChange = { lightness = it },
            onApply = { onApply(seed.toSeedArgb()) },
            onDismiss = onDismiss,
        )
    }
}

/** 弹层主体：标题 + 预览 + 三条取色条 + 操作按钮。 */
@Composable
private fun PickerContent(
    hue: Float,
    saturation: Float,
    lightness: Float,
    scheme: ColorScheme,
    onHueChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onLightnessChange: (Float) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val hueColors =
        remember {
            HUE_STOPS.map { stop -> Color.hsl(stop, HUE_GRADIENT_SATURATION, HUE_GRADIENT_LIGHTNESS) }
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.xl),
    ) {
        TitleRow(seedArgb = Color.hsl(hue, saturation, lightness).toSeedArgb())
        ColorPreview(scheme = scheme)
        Spacer(modifier = Modifier.height(spacing.md))
        GradientBar(
            label = stringResource(R.string.me_color_hue),
            valueText = "${hue.roundToInt()}$HUE_UNIT",
            value = hue,
            range = 0f..HUE_MAX,
            colors = hueColors,
            onValueChange = onHueChange,
        )
        GradientBar(
            label = stringResource(R.string.me_color_saturation),
            valueText = "${(saturation * PERCENT).roundToInt()}%",
            value = saturation,
            range = 0f..1f,
            colors =
                listOf(
                    Color.hsl(hue, 0f, HUE_GRADIENT_LIGHTNESS),
                    Color.hsl(hue, 1f, HUE_GRADIENT_LIGHTNESS),
                ),
            onValueChange = onSaturationChange,
        )
        GradientBar(
            label = stringResource(R.string.me_color_lightness),
            valueText = "${(lightness * PERCENT).roundToInt()}%",
            value = lightness,
            range = 0f..1f,
            colors =
                listOf(
                    Color.Black,
                    Color.hsl(hue, saturation, HUE_GRADIENT_LIGHTNESS),
                    Color.White,
                ),
            onValueChange = onLightnessChange,
        )
        Actions(onApply = onApply, onDismiss = onDismiss)
    }
}

/** 标题行：左侧标题，右侧当前颜色十六进制值。 */
@Composable
private fun TitleRow(seedArgb: Long) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = spacing.lg,
                    end = spacing.lg,
                    top = spacing.sm,
                    bottom = spacing.xs,
                ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.me_color_title),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = rgbHex(seedArgb),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 实时预览：主色圆点 + 容器底文字，直观看到应用后的柔和度与可读性。 */
@Composable
private fun ColorPreview(scheme: ColorScheme) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg)
                .background(color = scheme.primaryContainer, shape = MaterialTheme.shapes.medium)
                .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(AutopDimens.colorPreviewDot)
                    .background(color = scheme.primary, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "A",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onPrimary,
            )
        }
        Spacer(modifier = Modifier.width(spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.me_color_preview),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.me_color_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimaryContainer.copy(alpha = PREVIEW_HINT_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            listOf(scheme.primary, scheme.secondary, scheme.tertiary).forEach { color ->
                Box(
                    modifier =
                        Modifier
                            .size(AutopDimens.optionSwatch)
                            .background(color = color, shape = CircleShape)
                            .border(
                                width = AutopDimens.optionSwatchStroke,
                                color = scheme.onPrimaryContainer.copy(alpha = PREVIEW_HINT_ALPHA),
                                shape = CircleShape,
                            ),
                )
            }
        }
    }
}

/** 一条渐变取色条：点击或拖动设置数值。 */
@Composable
private fun GradientBar(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    colors: List<Color>,
    onValueChange: (Float) -> Unit,
) {
    val spacing = LocalSpacing.current
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        BarLabel(label = label, valueText = valueText)
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(AutopDimens.colorBarHeight)
                    .pointerInput(range) {
                        detectTapGestures { offset ->
                            onValueChange(valueAt(offset.x, size.width, range))
                        }
                    }.pointerInput(range) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                onValueChange(valueAt(offset.x, size.width, range))
                            },
                        ) { change, _ ->
                            onValueChange(valueAt(change.position.x, size.width, range))
                            change.consume()
                        }
                    },
        ) {
            drawPalette(colors = colors, value = value, range = range, outline = outline)
        }
    }
}

/** 名称与当前值。 */
@Composable
private fun BarLabel(
    label: String,
    valueText: String,
) {
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = onSurfaceVariant,
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
        )
    }
}

/** 渐变轨道与当前值滑块。 */
private fun DrawScope.drawPalette(
    colors: List<Color>,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    outline: Color,
) {
    val trackHeight = AutopDimens.colorBarTrack.toPx()
    val radius = trackHeight / 2f
    drawRoundRect(
        brush = Brush.horizontalGradient(colors),
        topLeft = Offset(x = 0f, y = (size.height - trackHeight) / 2f),
        size = Size(width = size.width, height = trackHeight),
        cornerRadius = CornerRadius(radius),
    )
    val thumbRadius = AutopDimens.colorBarThumb.toPx() / 2f
    val fraction =
        ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val center =
        Offset(
            x = (fraction * size.width).coerceIn(thumbRadius, size.width - thumbRadius),
            y = size.height / 2f,
        )
    drawCircle(color = Color.White, radius = thumbRadius, center = center)
    drawCircle(
        color = outline,
        radius = thumbRadius,
        center = center,
        style = Stroke(width = AutopDimens.colorBarThumbStroke.toPx()),
    )
}

/** 取消 / 应用按钮。 */
@Composable
private fun Actions(
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg)
                .padding(top = spacing.md),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.small) {
            Text(text = stringResource(R.string.me_color_cancel))
        }
        Spacer(modifier = Modifier.width(spacing.sm))
        Button(onClick = onApply, shape = MaterialTheme.shapes.small) {
            Text(text = stringResource(R.string.me_color_apply))
        }
    }
}

/** 触点横坐标 → 取值（越界钳制）。 */
private fun valueAt(
    x: Float,
    width: Int,
    range: ClosedFloatingPointRange<Float>,
): Float {
    if (width <= 0) return range.start
    val fraction = (x / width).coerceIn(0f, 1f)
    return range.start + fraction * (range.endInclusive - range.start)
}

/** ARGB → `#RRGGBB`（忽略 alpha）。 */
private fun rgbHex(seedArgb: Long): String =
    "#" + (seedArgb and HEX_RGB_MASK).toString(HEX_RADIX).padStart(HEX_LENGTH, '0').uppercase()

private const val HUE_MAX: Float = 360f
private const val HUE_UNIT: String = "°"
private const val PERCENT: Int = 100
private const val HUE_GRADIENT_SATURATION: Float = 0.85f
private const val HUE_GRADIENT_LIGHTNESS: Float = 0.55f
private const val PREVIEW_HINT_ALPHA: Float = 0.75f
private const val HEX_RGB_MASK: Long = 0xFFFFFF
private const val HEX_RADIX: Int = 16
private const val HEX_LENGTH: Int = 6

/** 色相条的分段停靠色（红→黄→绿→青→蓝→品红→红）。 */
private val HUE_STOPS: List<Float> = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f)
