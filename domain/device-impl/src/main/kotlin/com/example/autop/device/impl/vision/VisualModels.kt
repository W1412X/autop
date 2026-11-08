/**
 * 视觉解析内部模型（docs/design/visual-screen-parsing §6）：检测框/OCR 行/类别常量/矩形几何。
 * 对外产物是 NodeSnapshot；本包不依赖 Android，可 JVM 单测。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import kotlin.math.max
import kotlin.math.min

/** 检测框（单类/多类检测器输出）。[checked] 为勾选类状态三态（vp8 §6.3）：on/off/未知。 */
internal data class VisualDetection(
    val category: String,
    val confidence: Double,
    val bounds: Rect,
    val checked: Boolean? = null,
)

/** OCR 文本行。 */
internal data class VisualOcrLine(
    val text: String,
    val confidence: Double,
    val bounds: Rect,
)

/** 图标分类候选（vp9 §5.1/§5.2）：叶子框＋吸收所得 iconRegion（裁图优先用之）。 */
internal data class IconCandidate(
    val bounds: Rect,
    val iconRegion: Rect? = null,
)

/**
 * 检测类别常量。v1 键保留给单类兜底档（SubtypeRefiner 细化结果），v2 为 vp8 11 类
 * （ONNX metadata `names`，vp8 §8.2）；两套键同表映射，未知键按容器兜底。
 */
internal object VisualCategory {
    // ---- v1（单类兜底档，SubtypeRefiner 细化结果）----
    const val BUTTON: String = "button"
    const val ICON_CLICKABLE: String = "icon-clickable"
    const val INPUT_FIELD: String = "input-field"
    const val SWITCH: String = "switch"
    const val CHECKBOX: String = "checkbox"
    const val RADIO: String = "radio"
    const val SEEKBAR: String = "seekbar"
    const val TEXT_LABEL: String = "text-label"
    const val TAB: String = "tab"

    /** 标签栏分组（vp8 §8.2 几何识别，ADR-0008 决策 7）：非检测类，树构建阶段合成。 */
    const val TAB_GROUP: String = "tab-group"
    const val LIST_ITEM: String = "list-item"
    const val CONTAINER: String = "container"

    // ---- v2（vp8 11 类，与 ONNX metadata names 逐字一致）----
    const val TEXT: String = "text"
    const val ICON: String = "icon"
    const val IMAGE: String = "image"
    const val ITEM: String = "item"
    const val INPUT: String = "input"
    const val SLIDER: String = "slider"
    const val PROGRESS: String = "progress"

    /** icon 类（v1/v2）。 */
    val ICON_KINDS: Set<String> = setOf(ICON, ICON_CLICKABLE)

    /** text 类（v1/v2，OCR 是文本权威来源）。 */
    val TEXT_KINDS: Set<String> = setOf(TEXT, TEXT_LABEL)
}

/** IoU（交并比）。 */
internal fun Rect.iou(other: Rect): Double {
    val ix = max(0, min(r, other.r) - max(l, other.l))
    val iy = max(0, min(b, other.b) - max(t, other.t))
    val inter = ix.toLong() * iy
    val union = area.toLong() + other.area - inter
    return if (union > 0) inter.toDouble() / union else 0.0
}

/** [other] 面积的 ≥ [ratio] 落在本矩形内（包含判定，设计稿 §6.2）。 */
internal fun Rect.contains(
    other: Rect,
    ratio: Double = 0.7,
): Boolean {
    val ix = max(0, min(r, other.r) - max(l, other.l))
    val iy = max(0, min(b, other.b) - max(t, other.t))
    val inter = ix.toLong() * iy
    return other.area > 0 && inter.toDouble() / other.area >= ratio
}

/** [other] 中心是否落在本矩形内。 */
internal fun Rect.centerIn(other: Rect): Boolean =
    other.l <= centerX && centerX <= other.r && other.t <= centerY && centerY <= other.b

/** 两矩形是否相交（N204：内容区过滤）。 */
internal fun Rect.intersects(other: Rect): Boolean = l < other.r && r > other.l && t < other.b && b > other.t
