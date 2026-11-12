/**
 * 节点编辑表单（表驱动）：每种节点类型声明字段清单，简明模式只显示 [FieldSpec.simple] 的字段，
 * 高级模式显示全部。字段名与枚举选项的文案按资源名 `flow_field_<key>` / `flow_opt_<key>_<value>` 查找。
 */
@file:Suppress("CyclomaticComplexMethod", "LongMethod") // 表驱动字段表与控件分发

package com.example.autop.feature.flows.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.flows.R
import com.example.autop.feature.flows.stringByName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 字段的编辑控件类型。 */
internal enum class FieldKind {
    TEXT,
    MULTI,
    EXPR,
    NUMBER,
    BOOL,
    ENUM,
    LIST,
    DELAY,
    TARGET,
    COND,
    JSON,
    PRE,
}

/** 一个字段：[key] 即 JSON 键；[simple] 为真时简明模式也显示。 */
internal data class FieldSpec(
    val key: String,
    val kind: FieldKind,
    val simple: Boolean = false,
    val options: List<String> = emptyList(),
)

/** 各节点类型的字段表。 */
internal object NodeFields {
    private val MODES = listOf("a11y", "visual", "llm")

    private val COMMON =
        listOf(
            FieldSpec("name", FieldKind.TEXT, simple = true),
            FieldSpec("comment", FieldKind.MULTI),
            FieldSpec("enabled", FieldKind.BOOL),
            FieldSpec("timeoutMs", FieldKind.NUMBER),
            FieldSpec("trace", FieldKind.ENUM, options = listOf("full", "failures", "none")),
            FieldSpec("onFail", FieldKind.JSON),
            FieldSpec("confirm", FieldKind.JSON),
            FieldSpec("pre", FieldKind.PRE, simple = true),
        )

    fun forType(type: String): List<FieldSpec> = specific(type) + COMMON

    @Suppress("LongMethod") // 字段表：一种类型一段，是表驱动的数据本身
    private fun specific(type: String): List<FieldSpec> =
        when (type) {
            "tool" ->
                listOf(
                    FieldSpec("tool", FieldKind.TEXT, simple = true),
                    FieldSpec("target", FieldKind.TARGET, simple = true),
                    FieldSpec("args", FieldKind.JSON, simple = true),
                    FieldSpec("target2", FieldKind.TARGET),
                    FieldSpec("out", FieldKind.TEXT),
                )
            "observe" ->
                listOf(
                    FieldSpec("mode", FieldKind.ENUM, simple = true, options = MODES),
                    FieldSpec("region", FieldKind.JSON),
                    FieldSpec("out", FieldKind.TEXT),
                )
            "read" ->
                listOf(
                    FieldSpec(
                        "from",
                        FieldKind.ENUM,
                        simple = true,
                        options = listOf("target", "list", "page", "region", "screenshot"),
                    ),
                    FieldSpec("target", FieldKind.TARGET, simple = true),
                    FieldSpec(
                        "attr",
                        FieldKind.ENUM,
                        simple = true,
                        options = listOf("text", "desc", "hint", "state", "checked", "exists", "bounds", "count"),
                    ),
                    FieldSpec("region", FieldKind.JSON),
                    FieldSpec("out", FieldKind.TEXT, simple = true),
                )
            "wait" ->
                listOf(
                    FieldSpec(
                        "for",
                        FieldKind.ENUM,
                        simple = true,
                        options =
                            listOf(
                                "time",
                                "text",
                                "gone",
                                "element",
                                "elementGone",
                                "change",
                                "stable",
                                "app",
                                "notification",
                                "cond",
                                "until",
                            ),
                    ),
                    FieldSpec("ms", FieldKind.DELAY, simple = true),
                    FieldSpec("value", FieldKind.TEXT, simple = true),
                    FieldSpec("target", FieldKind.TARGET, simple = true),
                    FieldSpec("cond", FieldKind.COND),
                    FieldSpec("onTimeout", FieldKind.ENUM, options = listOf("fail", "continue")),
                    FieldSpec("page", FieldKind.TEXT),
                    FieldSpec("since", FieldKind.TEXT),
                    FieldSpec("package", FieldKind.TEXT),
                    FieldSpec("contains", FieldKind.TEXT),
                    FieldSpec("sender", FieldKind.TEXT),
                    FieldSpec("out", FieldKind.TEXT),
                )
            "llm" ->
                listOf(
                    FieldSpec("prompt", FieldKind.MULTI, simple = true),
                    FieldSpec("screen", FieldKind.BOOL, simple = true),
                    FieldSpec("output", FieldKind.JSON, simple = true),
                    FieldSpec("out", FieldKind.TEXT, simple = true),
                    FieldSpec("system", FieldKind.MULTI),
                    FieldSpec("images", FieldKind.LIST),
                    FieldSpec("validators", FieldKind.JSON),
                    FieldSpec("retry", FieldKind.JSON),
                    FieldSpec("limits", FieldKind.JSON),
                    FieldSpec("temperature", FieldKind.NUMBER),
                    FieldSpec("onInvalid", FieldKind.JSON),
                )
            "if" -> listOf(FieldSpec("cond", FieldKind.COND, simple = true))
            "switch" -> listOf(FieldSpec("on", FieldKind.EXPR, simple = true))
            "loop" ->
                listOf(
                    FieldSpec(
                        "mode",
                        FieldKind.ENUM,
                        simple = true,
                        options = listOf("count", "while", "until", "forever", "each"),
                    ),
                    FieldSpec("count", FieldKind.EXPR, simple = true),
                    FieldSpec("cond", FieldKind.COND, simple = true),
                    FieldSpec("items", FieldKind.EXPR, simple = true),
                    FieldSpec("itemVar", FieldKind.TEXT),
                    FieldSpec("indexVar", FieldKind.TEXT),
                    FieldSpec("maxIterations", FieldKind.NUMBER),
                    FieldSpec("maxDurationMs", FieldKind.NUMBER),
                    FieldSpec("delayMs", FieldKind.DELAY),
                    FieldSpec("continueOnFail", FieldKind.BOOL),
                    FieldSpec("resumeKey", FieldKind.TEXT),
                )
            "call" ->
                listOf(
                    FieldSpec("flow", FieldKind.TEXT, simple = true),
                    FieldSpec("args", FieldKind.JSON, simple = true),
                    FieldSpec("out", FieldKind.TEXT, simple = true),
                )
            "agent" ->
                listOf(
                    FieldSpec("goal", FieldKind.MULTI, simple = true),
                    FieldSpec("out", FieldKind.TEXT, simple = true),
                    FieldSpec("tools", FieldKind.LIST),
                    FieldSpec("maxSteps", FieldKind.NUMBER),
                    FieldSpec("maxDurationMs", FieldKind.NUMBER),
                )
            "api" ->
                listOf(
                    FieldSpec("endpoint", FieldKind.TEXT, simple = true),
                    FieldSpec(
                        "method",
                        FieldKind.ENUM,
                        simple = true,
                        options = listOf("GET", "POST", "PUT", "PATCH", "DELETE"),
                    ),
                    FieldSpec("path", FieldKind.TEXT, simple = true),
                    FieldSpec("body", FieldKind.JSON, simple = true),
                    FieldSpec("out", FieldKind.TEXT, simple = true),
                    FieldSpec("query", FieldKind.JSON),
                    FieldSpec("headers", FieldKind.JSON),
                    FieldSpec("bodyType", FieldKind.ENUM, options = listOf("none", "json", "form", "text")),
                    FieldSpec("expectStatus", FieldKind.JSON),
                )
            "set" -> listOf(FieldSpec("vars", FieldKind.JSON, simple = true))
            "state" ->
                listOf(
                    FieldSpec("op", FieldKind.ENUM, simple = true, options = listOf("get", "set", "inc", "delete")),
                    FieldSpec("key", FieldKind.TEXT, simple = true),
                    FieldSpec("value", FieldKind.EXPR, simple = true),
                    FieldSpec("out", FieldKind.TEXT, simple = true),
                    FieldSpec("scope", FieldKind.ENUM, options = listOf("task", "flow")),
                    FieldSpec("ttlMs", FieldKind.NUMBER),
                    FieldSpec("resetDaily", FieldKind.BOOL),
                    FieldSpec("default", FieldKind.EXPR),
                )
            "log" ->
                listOf(
                    FieldSpec("message", FieldKind.MULTI, simple = true),
                    FieldSpec("level", FieldKind.ENUM, options = listOf("info", "warn", "error")),
                )
            "end" ->
                listOf(
                    FieldSpec("status", FieldKind.ENUM, simple = true, options = listOf("success", "failure")),
                    FieldSpec("message", FieldKind.TEXT, simple = true),
                    FieldSpec("outputs", FieldKind.JSON, simple = true),
                )
            else -> emptyList()
        }
}

/** 节点表单：[draft] 为节点的 JSON 对象，任何改动通过 [onChange] 返回新对象。 */
@Composable
internal fun NodeForm(
    type: String,
    draft: JsonObject,
    advanced: Boolean,
    onChange: (JsonObject) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.md)) {
        NodeFields.forType(type).filter { advanced || it.simple }.forEach { spec ->
            val label = stringByName("flow_field_${spec.key}", spec.key)
            Field(spec = spec, label = label, draft = draft, onChange = onChange)
        }
    }
}

@Composable
private fun Field(
    spec: FieldSpec,
    label: String,
    draft: JsonObject,
    onChange: (JsonObject) -> Unit,
) {
    val key = spec.key
    val set: (JsonElement?) -> Unit = { value ->
        onChange(JsonObject(if (value == null || value is JsonNull) draft - key else draft + (key to value)))
    }
    val current = draft[key]
    when (spec.kind) {
        FieldKind.TEXT, FieldKind.EXPR ->
            TextField(label, text(current), multi = false) {
                set(
                    it
                        .takeIf { s ->
                            s.isNotEmpty()
                        }?.let(::JsonPrimitive),
                )
            }
        FieldKind.MULTI ->
            TextField(label, text(current), multi = true) {
                set(
                    it
                        .takeIf { s ->
                            s.isNotEmpty()
                        }?.let(::JsonPrimitive),
                )
            }
        FieldKind.NUMBER ->
            TextField(label, text(current), multi = false) { s ->
                set(
                    s.trim().toLongOrNull()?.let(::JsonPrimitive) ?: s.trim().toDoubleOrNull()?.let(::JsonPrimitive),
                )
            }
        FieldKind.BOOL ->
            SwitchRow(
                title = label,
                subtitle = null,
                checked =
                    (current as? JsonPrimitive)?.booleanOrNull ?: defaultBool(spec.key),
                onCheckedChange = { set(JsonPrimitive(it)) },
            )
        FieldKind.ENUM -> EnumField(spec, label, text(current)) { set(JsonPrimitive(it)) }
        FieldKind.LIST ->
            TextField(label, ((current as? JsonArray)?.joinToString(", ") { text(it) }).orEmpty(), multi = false) { s ->
                val items = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                set(if (items.isEmpty()) null else JsonArray(items.map(::JsonPrimitive)))
            }
        FieldKind.DELAY -> TextField(label, delayText(current), multi = false) { set(parseDelay(it)) }
        FieldKind.TARGET -> JsonField(label, current, TARGET_TEMPLATES, set, reselectable = true)
        FieldKind.COND -> JsonField(label, current, COND_TEMPLATES, set)
        FieldKind.JSON -> JsonField(label, current, emptyList(), set)
        FieldKind.PRE -> PreField(label, current, draft, set)
    }
}

/**
 * 前置断言编辑（docs/design/flows-precondition-selfrepair.md P6）。
 *
 * 简明模式给一行摘要（条件描述 + 策略徽标），下面是 JSON 编辑与模板；
 * "从 expect 复制"把本节点的期望效果翻成前置断言。
 */
@Composable
private fun PreField(
    label: String,
    current: JsonElement?,
    draft: JsonObject,
    onChange: (JsonElement?) -> Unit,
) {
    val spacing = LocalSpacing.current
    val pre = current as? JsonObject
    val policy = pre?.policy() ?: "fail"
    Text(
        text =
            stringByName("flow_field_pre_summary", "前置") + "：" + preSummary(pre) +
                " · " + stringByName("flow_opt_pre_$policy", policy),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = spacing.screenEdge, top = spacing.md),
    )
    JsonField(label, current, PRE_TEMPLATES, onChange)
    val copied = preFromExpect(draft["expect"])
    if (copied != null) {
        AutopTextButton(
            text = stringResource(R.string.editor_copy_expect_to_pre),
            onClick = { onChange(copied) },
            modifier = Modifier.padding(horizontal = spacing.screenEdge),
        )
    }
}

/** `pre` 的条件描述（结构化描述，不含界面文案）。 */
@Composable
private fun preSummary(pre: JsonObject?): String {
    val cond = pre?.get("cond") as? JsonObject ?: return stringByName("flow_pre_unset", "未设置")
    val kind = cond["kind"].textOrEmpty()
    return when (kind) {
        "screenText" ->
            "页面文字${cond["op"].textOrEmpty().ifEmpty { "包含" }}「${cond["value"].textOrEmpty()}」"
        "element" -> "元素状态（${cond["op"].textOrEmpty().ifEmpty { "exists" }}）"
        "app" -> "前台应用为「${cond["value"].textOrEmpty()}」"
        "expr" -> "表达式「${cond["expr"].textOrEmpty()}」"
        "screenChanged" -> "屏幕相对 ${cond["since"].textOrEmpty()} 已变化"
        "lastOk" -> "节点 ${cond["node"].textOrEmpty()} 最近一次成功"
        "llm" -> "模型判断"
        "all", "any", "not" -> "组合条件"
        else -> kind
    }
}

/** `pre` 的策略徽标文本（fail = 断言，wait = 等待）。 */
private fun JsonObject.policy(): String = (this["policy"] as? JsonPrimitive)?.contentOrNull ?: "fail"

/** 把节点的期望效果翻成前置断言；无法对应时返回 null。 */
private fun preFromExpect(expect: JsonElement?): JsonObject? {
    val e = expect as? JsonObject ?: return null
    val kind = (e["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val cond =
        when (kind) {
            "text" ->
                JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive("screenText"),
                        "op" to JsonPrimitive("contains"),
                        "value" to JsonPrimitive(e["text"]?.textOrEmpty().orEmpty()),
                    ),
                )
            "element" ->
                e["target"]?.let {
                    JsonObject(
                        mapOf("kind" to JsonPrimitive("element"), "op" to JsonPrimitive("exists"), "target" to it),
                    )
                }
            "app" ->
                JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive("app"),
                        "value" to JsonPrimitive(e["app"]?.textOrEmpty().orEmpty()),
                    ),
                )
            else -> null
        } ?: return null
    return JsonObject(mapOf("cond" to cond, "policy" to JsonPrimitive("fail")))
}

private fun JsonElement?.textOrEmpty(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun defaultBool(key: String): Boolean = key == "enabled" || key == "feedback"

private fun text(element: JsonElement?): String = (element as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun delayText(element: JsonElement?): String =
    when (element) {
        is JsonObject -> "${text(element["min"])}-${text(element["max"])}"
        null, is JsonNull -> ""
        else -> text(element)
    }

/** `800` → 固定值；`800-1500` → 区间。 */
private fun parseDelay(raw: String): JsonElement? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    val parts = s.split('-')
    val lo = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
    val hi = parts.getOrNull(1)?.trim()?.toLongOrNull()
    return if (hi ==
        null
    ) {
        JsonPrimitive(lo)
    } else {
        JsonObject(mapOf("min" to JsonPrimitive(lo), "max" to JsonPrimitive(hi)))
    }
}

@Composable
private fun TextField(
    label: String,
    value: String,
    multi: Boolean,
    onValue: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = spacing.screenEdge, top = spacing.md),
    )
    AutopInputBar(
        value = value,
        onValueChange = onValue,
        spec = InputBarSpec(placeholder = label, maxLines = if (multi) MULTI_LINES else 1),
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge, vertical = spacing.xs),
    )
}

private const val MULTI_LINES = 6

@Composable
private fun EnumField(
    spec: FieldSpec,
    label: String,
    value: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    OptionRow(
        title = label,
        subtitle = null,
        value =
            value
                .ifEmpty {
                    stringResource(
                        R.string.editor_unset,
                    )
                }.let { stringByName("flow_opt_${spec.key}_$it", it) },
        onClick = { open = true },
    )
    if (open) {
        OptionSheet(
            title = label,
            options = spec.options.map { OptionItem(it, stringByName("flow_opt_${spec.key}_$it", it)) },
            selectedId = value.ifEmpty { null },
            onSelect = {
                open = false
                onPick(it)
            },
            onDismiss = { open = false },
        )
    }
}

/** JSON 编辑框：输入合法才提交；非法时显示错误并保留输入。 */
@Composable
private fun JsonField(
    label: String,
    current: JsonElement?,
    templates: List<Pair<String, String>>,
    onValue: (JsonElement?) -> Unit,
    reselectable: Boolean = false,
) {
    val reselect = LocalTargetReselect.current
    val spacing = LocalSpacing.current
    var raw by remember(current == null) { mutableStateOf(current?.toString().orEmpty()) }
    var error by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    TextField(label = label, value = raw, multi = true) { input ->
        raw = input
        if (input.isBlank()) {
            error = false
            onValue(null)
        } else {
            val parsed = runCatching { Json.parseToJsonElement(input) }.getOrNull()
            error = parsed == null
            if (parsed != null) onValue(parsed)
        }
    }
    if (error) {
        Text(
            text = stringResource(R.string.editor_json_invalid),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = spacing.screenEdge),
        )
    }
    if (templates.isNotEmpty()) {
        AutopTextButton(
            text = stringResource(R.string.editor_template),
            onClick = { picking = true },
            modifier = Modifier.padding(horizontal = spacing.screenEdge),
        )
    }
    if (reselectable && reselect != null) {
        AutopTextButton(
            text = stringResource(R.string.editor_reselect),
            onClick = {
                reselect { picked ->
                    raw = picked.toString()
                    error = false
                    onValue(picked)
                }
            },
            modifier = Modifier.padding(horizontal = spacing.screenEdge),
        )
    }
    if (picking) {
        OptionSheet(
            title = label,
            options = templates.map { OptionItem(it.first, stringByName("flow_tpl_${it.first}", it.first)) },
            selectedId = null,
            onSelect = { id ->
                picking = false
                val template = templates.firstOrNull { it.first == id }?.second ?: return@OptionSheet
                raw = template
                error = false
                onValue(Json.parseToJsonElement(template))
            },
            onDismiss = { picking = false },
        )
    }
}

/** 目标字段的“在手机上重新选取”：由编辑器屏幕提供，参数为接收结果的回调。 */
internal val LocalTargetReselect: ProvidableCompositionLocal<((onPicked: (JsonElement) -> Unit) -> Unit)?> =
    compositionLocalOf { null }

/** 目标描述模板。 */
private val TARGET_TEMPLATES: List<Pair<String, String>> =
    listOf(
        "target_text" to """{"by":"text","value":""}""",
        "target_icon" to """{"by":"icon","value":""}""",
        "target_desc" to """{"by":"desc","value":""}""",
        "target_hint" to """{"by":"hint","value":""}""",
        "target_in" to """{"by":"text","value":"","within":{"by":"text","value":""}}""",
        "target_nth" to """{"by":"text","value":"","pick":"nth","nth":1}""",
    )

/** 条件模板（字段 `kind` 区分）。 */
private val COND_TEMPLATES: List<Pair<String, String>> =
    listOf(
        "cond_expr" to """{"kind":"expr","expr":""}""",
        "cond_screen_text" to """{"kind":"screenText","op":"contains","value":""}""",
        "cond_element" to """{"kind":"element","op":"exists","target":{"by":"text","value":""}}""",
        "cond_app" to """{"kind":"app","value":""}""",
        "cond_time" to """{"kind":"time","between":["08:00","22:00"]}""",
        "cond_device" to """{"kind":"device","metric":"battery","op":">=","value":20}""",
        "cond_last_ok" to """{"kind":"lastOk","node":""}""",
        "cond_llm" to """{"kind":"llm","prompt":"","screen":true}""",
        "cond_all" to """{"kind":"all","of":[]}""",
        "cond_any" to """{"kind":"any","of":[]}""",
        "cond_not" to """{"kind":"not","of":{"kind":"expr","expr":""}}""",
    )

/** 前置断言模板（整个 `pre` 对象）。 */
private val PRE_TEMPLATES: List<Pair<String, String>> =
    listOf(
        "pre_text_fail" to """{"cond":{"kind":"screenText","op":"contains","value":""},"policy":"fail"}""",
        "pre_text_wait" to """{"cond":{"kind":"screenText","op":"contains","value":""},"policy":"wait"}""",
        "pre_app" to """{"cond":{"kind":"app","value":""},"policy":"fail"}""",
        "pre_element" to
            """{"cond":{"kind":"element","op":"exists","target":{"by":"text","value":""}},"policy":"fail"}""",
    )

/** 供编辑器保存时检查某字段是否为整数（避免未用导入）。 */
internal fun JsonElement.asLongOrNull(): Long? = (this as? JsonPrimitive)?.longOrNull
