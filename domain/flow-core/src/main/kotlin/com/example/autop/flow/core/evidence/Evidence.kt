/**
 * 流程证据层（docs/design/flows-contracts-evidence §3）：真实执行留下的
 * 动作前后屏幕证据与命中元素；观测文本（统一 JSON）解析为带稳定键的节点行。
 * 纯 Kotlin，不依赖 Android。
 */
package com.example.autop.flow.core.evidence

import com.example.autop.core.common.AutopJson
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenNode
import com.example.autop.flow.Condition
import com.example.autop.flow.TargetBy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 证据里的一个屏幕快照。 */
public data class EvidenceScreen(
    /** 前台包名。 */
    val app: String? = null,
    /** 当前页面提示（窗口 page 字段）。 */
    val page: String? = null,
    /** 观测指纹（弱证据时的唯一凭据）。 */
    val fingerprint: String? = null,
    /** 弱证据：只有包名/页面/指纹，没有结构化节点（llm 观测或解析失败）。 */
    val weak: Boolean = false,
    /** 节点行（深度优先展开，带稳定键）。 */
    val nodes: List<ScreenNode> = emptyList(),
)

/** 一步执行的证据：动作前后的屏幕与命中元素。 */
public data class EvidenceStep(
    val tool: String,
    val args: JsonObject = JsonObject(emptyMap()),
    /** 命中元素回执（`_hit`）。 */
    val hit: JsonObject? = null,
    val pre: EvidenceScreen? = null,
    val post: EvidenceScreen? = null,
    val ok: Boolean = true,
)

/** 统一 JSON 观测文本 → 证据节点行。 */
public object EvidenceWireParser {
    /**
     * 解析 `screen` 根对象的观测文本为带稳定键的节点行；解析不出返回空列表，
     * 由调用方按弱证据处理（只有包名/页面/指纹）。
     */
    public fun parseRows(
        text: String,
        volatilePatterns: List<Regex> = defaultVolatilePatterns(),
    ): List<ScreenNode> {
        val root =
            runCatching { AutopJson.default.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return emptyList()
        val screen = root[W.ROOT_SCREEN] as? JsonObject ?: return emptyList()
        val windows = screen[W.FIELD_WINDOWS] as? JsonArray ?: return emptyList()
        return windows
            .mapNotNull { it as? JsonObject }
            .flatMapIndexed { w, win ->
                val app = win.text(W.FIELD_APP).orEmpty()
                (win[W.FIELD_KIDS] as? JsonArray)
                    .orEmpty()
                    .mapIndexedNotNull { i, kid ->
                        (kid as? JsonObject)?.let { parseNode(it, "$w.$i", app, volatilePatterns) }
                    }.flatMap { flatten(it) }
            }
    }

    private fun parseNode(
        obj: JsonObject,
        position: String,
        app: String,
        volatilePatterns: List<Regex>,
    ): ScreenNode {
        val kids =
            (obj[W.FIELD_KIDS] as? JsonArray)
                .orEmpty()
                .mapIndexedNotNull { i, kid ->
                    (kid as? JsonObject)?.let { parseNode(it, "$position.$i", app, volatilePatterns) }
                }
        val text = obj.text(W.FIELD_TEXT)
        val desc = obj.text(W.FIELD_DESC)
        val hint = obj.text(W.FIELD_HINT)
        val icon = obj.text(W.FIELD_ICON)
        val vid = obj.text(W.FIELD_VID)
        val tag = obj.text(W.FIELD_TAG).orEmpty()
        val label = listOfNotNull(text, desc, hint, icon).firstOrNull().orEmpty()
        return ScreenNode(
            tag = tag,
            text = text,
            icon = icon,
            desc = desc,
            hint = hint,
            state = obj.text(W.FIELD_STATE),
            cls = obj.text(W.FIELD_CLS),
            vid = vid,
            act = obj.strList(W.FIELD_ACT),
            st = obj.strList(W.FIELD_ST),
            bounds = rectOf(obj[W.FIELD_BOUNDS]) ?: Rect(0, 0, 0, 0),
            at = pointOf(obj[W.FIELD_AT]),
            more = obj.text(W.FIELD_MORE),
            kids = kids,
            key = stableKeyOf(app, vid, tag, label, position, volatilePatterns),
        )
    }

    private fun flatten(node: ScreenNode): List<ScreenNode> = listOf(node) + node.kids.flatMap { flatten(it) }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    @Suppress("MagicNumber") // 坐标数组固定 [l, t, r, b]，下标即坐标位置
    private fun rectOf(element: JsonElement?): Rect? {
        val values = (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        return if (values != null && values.size == RECT_COORDS) {
            Rect(values[0], values[1], values[2], values[3])
        } else {
            null
        }
    }

    private fun pointOf(element: JsonElement?): Point? {
        val values = (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        return if (values != null && values.size == POINT_COORDS) Point(values[0], values[1]) else null
    }
}

private const val RECT_COORDS: Int = 4
private const val POINT_COORDS: Int = 2

/** 证据行的稳定键：同 `NodeKeys.stableNodeKey` 口径（包名|viewId|role|稳定 label|位置路径）。 */
internal fun stableKeyOf(
    app: String,
    vid: String?,
    tag: String,
    label: String,
    position: String,
    volatilePatterns: List<Regex>,
): String {
    val stable = volatilePatterns.fold(label) { acc, pattern -> pattern.replace(acc, "") }
    val keep = if (vid.isNullOrBlank()) LABEL_CHARS else LABEL_CHARS_WITH_VIEW_ID
    return listOf(app, vid.orEmpty(), tag, stable.take(keep), position).joinToString(KEY_SEPARATOR)
}

/** 稳定键分隔符（口径同 device-core 的 NodeKeys.KEY_SEPARATOR）。 */
private const val KEY_SEPARATOR: String = " | "

private const val LABEL_CHARS: Int = 20
private const val LABEL_CHARS_WITH_VIEW_ID: Int = 8

/** 观测 JSON 字段词表（与 device-core 的 `ScreenJsonWire` 同词表；CORE 不能依赖 CORE，故各持一份）。 */
private object W {
    const val ROOT_SCREEN: String = "screen"
    const val FIELD_WINDOWS: String = "windows"
    const val FIELD_APP: String = "app"
    const val FIELD_KIDS: String = "kids"
    const val FIELD_TAG: String = "tag"
    const val FIELD_TEXT: String = "text"
    const val FIELD_ICON: String = "icon"
    const val FIELD_DESC: String = "desc"
    const val FIELD_HINT: String = "hint"
    const val FIELD_STATE: String = "state"
    const val FIELD_CLS: String = "cls"
    const val FIELD_VID: String = "vid"
    const val FIELD_ACT: String = "act"
    const val FIELD_ST: String = "st"
    const val FIELD_BOUNDS: String = "bounds"
    const val FIELD_AT: String = "at"
    const val FIELD_MORE: String = "more"
}

/** 预编译易变文本模式（口径同 serializer_rules.json 的 volatileLabelPatterns）。 */
public fun defaultVolatilePatterns(): List<Regex> = DEFAULT_VOLATILE.map(::Regex)

/** 与 `assets/rules/serializer_rules.json` 的 volatileLabelPatterns 同口径。 */
private val DEFAULT_VOLATILE: List<String> = listOf("\\d{1,2}:\\d{2}", "\\d+\\+", "\\d+")

/** 条件在证据屏幕上的判定（app / element / screenText / all / any / not）。 */
public fun conditionHolds(
    cond: Condition,
    screen: EvidenceScreen,
): Boolean =
    when (cond) {
        is Condition.App ->
            screen.app != null &&
                screen.app == cond.value &&
                (cond.page == null || screen.page?.contains(cond.page!!) == true)
        is Condition.ScreenText ->
            cond.value.isNotEmpty() && screenTextOf(screen).contains(cond.value)
        is Condition.Element -> elementHolds(cond, screen)
        is Condition.All -> cond.of.all { conditionHolds(it, screen) }
        is Condition.Any -> cond.of.any { conditionHolds(it, screen) }
        is Condition.Not -> !conditionHolds(cond.of, screen)
        else -> false
    }

/** 屏幕可见文字拼接（screenText 条件的判定面）。 */
public fun screenTextOf(screen: EvidenceScreen): String =
    screen.nodes.joinToString(" ") { listOfNotNull(it.text, it.desc, it.hint).joinToString(" ") }

/** 节点按目标定位方式取的标签。 */
public fun labelFieldOf(
    node: ScreenNode,
    by: TargetBy,
): String =
    when (by) {
        TargetBy.TEXT -> node.text.orEmpty()
        TargetBy.DESC -> node.desc.orEmpty()
        TargetBy.HINT -> node.hint.orEmpty()
        TargetBy.ICON -> node.icon.orEmpty()
        TargetBy.TAG -> node.tag
    }

/** 条件的文字描述（候选与核验问题共用）。 */
public fun describeCondition(cond: Condition): String =
    when (cond) {
        is Condition.App -> "前台应用为「${cond.value}」"
        is Condition.ScreenText -> "页面文字包含「${cond.value}」"
        is Condition.Element -> "元素存在「${cond.target.value ?: cond.target.tag ?: "?"}」"
        is Condition.All -> cond.of.joinToString("，") { describeCondition(it) }
        is Condition.Any -> cond.of.joinToString(" 或 ") { describeCondition(it) }
        is Condition.Not -> "非（${describeCondition(cond.of)}）"
        else -> cond.toString()
    }

private fun elementHolds(
    cond: Condition.Element,
    screen: EvidenceScreen,
): Boolean {
    val target = cond.target
    val tag = target.tag
    if (target.value == null && tag != null) return screen.nodes.any { it.tag == tag }
    val value = target.value ?: return false
    return screen.nodes.any { labelFieldOf(it, target.by).contains(value) }
}
