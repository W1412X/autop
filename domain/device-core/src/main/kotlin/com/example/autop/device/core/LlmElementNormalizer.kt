/**
 * llm 模式元素后处理（docs/design/observation-modes-coordinate-actions §4.5）：
 * 模型扁平元素 → 坐标换算 → 过滤去重 → 包含关系建树 → 统一节点树。
 */
package com.example.autop.device.core

import com.example.autop.device.Rect
import com.example.autop.device.ScreenNode
import com.example.autop.llm.CoordSpace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/** 模型 box 的分量顺序。 */
public enum class BboxOrder {
    XYXY,
    YXYX,
    ;

    public companion object {
        /** `quirks.bboxOrder` → 枚举（未知值按 [XYXY]）。 */
        public fun parse(value: String?): BboxOrder = if (value.equals("yxyx", ignoreCase = true)) YXYX else XYXY
    }
}

/** 后处理参数。 */
public data class LlmNormalizerConfig(
    val space: CoordSpace,
    val order: BboxOrder,
    /** 送入模型的图片尺寸（[CoordSpace.IMAGE_PIXELS] 用）。 */
    val imageWidth: Int,
    val imageHeight: Int,
    /** 局部识别区域（0–1000），null=整屏。 */
    val region: Rect? = null,
    val maxElements: Int,
    val minAreaRatio: Double,
    val dupIou: Double,
    /** 允许的 tag 词表（来自 serializer_rules）。 */
    val tags: Set<String>,
)

/** 元素后处理。 */
public object LlmElementNormalizer {
    /** 解析模型参数并返回节点树；参数缺少合法 `elements` 时返回 null（调用方重试）。 */
    public fun normalize(
        params: JsonObject,
        config: LlmNormalizerConfig,
    ): List<ScreenNode>? {
        val raw = (params[ROOT_ELEMENTS] as? JsonArray) ?: return null
        val candidates =
            raw
                .mapNotNull { it as? JsonObject }
                .mapNotNull { parse(it, config) }
                .let { dedupe(it, config.dupIou) }
                .let { limit(it, config.maxElements) }
                .sortedWith(READING_ORDER)
        return buildTree(candidates)
    }

    private data class Element(
        val tag: String,
        val text: String?,
        val icon: String?,
        val desc: String?,
        val hint: String?,
        val state: String?,
        val act: List<String>,
        val st: List<String>,
        val bounds: Rect,
    )

    private fun parse(
        obj: JsonObject,
        config: LlmNormalizerConfig,
    ): Element? {
        val box = (obj[FIELD_BOX] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull } ?: return null
        if (box.size != BOX_SIZE || box.any { it == null }) return null
        val raw = box.map { it ?: return null }
        val first = raw[0]
        val second = raw[1]
        val third = raw[2]
        val fourth = raw.last()
        val xyxy =
            if (config.order == BboxOrder.YXYX) {
                listOf(second, first, fourth, third)
            } else {
                listOf(first, second, third, fourth)
            }
        val mapped = remap(scale(xyxy, config), config.region)
        val clamped =
            Rect(
                mapped[0].coerceIn(COORD_MIN, COORD_MAX),
                mapped[1].coerceIn(COORD_MIN, COORD_MAX),
                mapped[2].coerceIn(COORD_MIN, COORD_MAX),
                mapped.last().coerceIn(COORD_MIN, COORD_MAX),
            )
        if (clamped.l >= clamped.r || clamped.t >= clamped.b) return null
        if (clamped.area.toDouble() / AREA_SCALE < config.minAreaRatio) return null
        val act = wordsOf(obj[FIELD_ACT], ACT_WORDS)
        val st = wordsOf(obj[FIELD_ST], ST_WORDS)
        val tag =
            obj[FIELD_TAG]
                ?.string()
                ?.takeIf { it in config.tags }
                ?: inferTag(act)
        return Element(
            tag = tag,
            text = obj[FIELD_TEXT]?.string()?.takeIf { it.isNotEmpty() },
            icon = obj[FIELD_ICON]?.string()?.takeIf { it.isNotEmpty() },
            desc = obj[FIELD_DESC]?.string()?.takeIf { it.isNotEmpty() },
            hint = obj[FIELD_HINT]?.string()?.takeIf { it.isNotEmpty() },
            state = stateOf(obj[FIELD_STATE]?.string()),
            act = act,
            st = st,
            bounds = clamped,
        )
    }

    /** 统一到 0–1000（相对送入的图片）。 */
    private fun scale(
        xyxy: List<Int>,
        config: LlmNormalizerConfig,
    ): List<Int> =
        when (config.space) {
            CoordSpace.NORMALIZED_1000 -> xyxy
            CoordSpace.IMAGE_PIXELS -> {
                val w = config.imageWidth.coerceAtLeast(1)
                val h = config.imageHeight.coerceAtLeast(1)
                listOf(
                    scaleValue(xyxy[0], w),
                    scaleValue(xyxy[1], h),
                    scaleValue(xyxy[2], w),
                    scaleValue(xyxy.last(), h),
                )
            }
        }

    private fun scaleValue(
        value: Int,
        size: Int,
    ): Int = (value.toDouble() / size * COORD_SCALE).roundToInt()

    /** 局部识别区域映射回整屏 0–1000。 */
    private fun remap(
        xyxy: List<Int>,
        region: Rect?,
    ): List<Int> {
        if (region == null) return xyxy
        val w = region.width
        val h = region.height
        return listOf(
            region.l + xyxy[0] * w / COORD_SCALE,
            region.t + xyxy[1] * h / COORD_SCALE,
            region.l + xyxy[2] * w / COORD_SCALE,
            region.t + xyxy.last() * h / COORD_SCALE,
        )
    }

    private fun wordsOf(
        element: kotlinx.serialization.json.JsonElement?,
        allowed: Set<String>,
    ): List<String> = (element as? JsonArray)?.mapNotNull { it.string() }?.filter { it in allowed }.orEmpty()

    private fun kotlinx.serialization.json.JsonElement.string(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** 词表外 tag 按 act 推断（edit→input，click→btn，否则 text）。 */
    private fun inferTag(act: List<String>): String =
        when {
            ScreenJsonWire.ACT_EDIT in act -> TAG_INPUT
            ScreenJsonWire.ACT_CLICK in act -> TAG_BUTTON
            else -> TAG_TEXT
        }

    /** 视觉/llm 的 state 统一为 on/off。 */
    private fun stateOf(raw: String?): String? =
        when (raw?.lowercase()) {
            "on", "true", "已开启", "打开" -> STATE_ON
            "off", "false", "已关闭", "关闭" -> STATE_OFF
            else -> null
        }

    /** 同 tag 且 IoU 超阈值的重复项保留文字更长的一条。 */
    private fun dedupe(
        elements: List<Element>,
        dupIou: Double,
    ): List<Element> =
        elements.filterIndexed { index, element ->
            elements.withIndex().none { (otherIndex, other) ->
                otherIndex != index &&
                    other.tag == element.tag &&
                    Containment.iou(other.bounds, element.bounds) > dupIou &&
                    lengthOf(other) > lengthOf(element)
            }
        }

    private fun lengthOf(element: Element): Int = element.text?.length ?: 0

    /** 超出上限时优先保留可操作元素（同组保持阅读序）。 */
    private fun limit(
        elements: List<Element>,
        maxElements: Int,
    ): List<Element> {
        if (maxElements <= 0 || elements.size <= maxElements) return elements
        val sorted = elements.sortedWith(READING_ORDER)
        return sorted.filter { it.act.isNotEmpty() }.take(maxElements).let { keep ->
            keep + sorted.filter { it.act.isEmpty() }.take(maxElements - keep.size)
        }
    }

    /** 包含关系建树；同级按阅读序（先 y1 再 x1）。 */
    private fun buildTree(elements: List<Element>): List<ScreenNode> {
        val boxes = elements.map { it.bounds }
        val parents = elements.map { Containment.parentIndexOf(it.bounds, boxes) }
        val children = List(elements.size) { mutableListOf<Int>() }
        val roots = mutableListOf<Int>()
        elements.indices.forEach { i ->
            val parent = parents[i]
            if (parent == null) roots += i else children[parent] += i
        }

        fun node(i: Int): ScreenNode = elements[i].toNode(children[i].map(::node))
        return roots.map(::node)
    }

    private fun Element.toNode(kids: List<ScreenNode>): ScreenNode =
        ScreenNode(
            tag = tag,
            text = text,
            icon = icon,
            desc = desc,
            hint = hint,
            state = state,
            act = act,
            st = st,
            bounds = bounds,
            at =
                if (act.isEmpty()) {
                    null
                } else {
                    com.example.autop.device
                        .Point(bounds.centerX, bounds.centerY)
                },
            kids = kids,
        )

    private val READING_ORDER: Comparator<Element> = compareBy({ it.bounds.t }, { it.bounds.l })

    private val ACT_WORDS: Set<String> =
        setOf(ScreenJsonWire.ACT_CLICK, ScreenJsonWire.ACT_EDIT, ScreenJsonWire.ACT_SCROLL)

    private val ST_WORDS: Set<String> =
        setOf(
            ScreenJsonWire.ST_CHECKED,
            ScreenJsonWire.ST_UNCHECKED,
            ScreenJsonWire.ST_SELECTED,
            ScreenJsonWire.ST_DISABLED,
        )

    private const val ROOT_ELEMENTS = "elements"
    private const val FIELD_BOX = "box"
    private const val FIELD_TAG = "tag"
    private const val FIELD_TEXT = "text"
    private const val FIELD_ICON = "icon"
    private const val FIELD_DESC = "desc"
    private const val FIELD_HINT = "hint"
    private const val FIELD_STATE = "state"
    private const val FIELD_ACT = "act"
    private const val FIELD_ST = "st"
    private const val BOX_SIZE = 4
    private const val COORD_MIN = 0
    private const val COORD_MAX = 1000
    private const val COORD_SCALE = 1000
    private const val AREA_SCALE = 1_000_000.0
    private const val TAG_INPUT = "input"
    private const val TAG_BUTTON = "btn"
    private const val TAG_TEXT = "text"
    private const val STATE_ON = "on"
    private const val STATE_OFF = "off"
}
