/**
 * 流程界面的共用文案与参数表单工具。
 */
package com.example.autop.feature.flows

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.flow.FlowParam
import com.example.autop.flow.NodeStatus
import com.example.autop.flow.ParamType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 运行状态（`FlowRunStatus` 名称）→ 中文。 */
@Composable
internal fun runStatusLabel(status: String): String =
    stringResource(
        when (status.uppercase()) {
            "RUNNING" -> R.string.flow_status_running
            "PAUSED" -> R.string.flow_status_paused
            "SUCCEEDED" -> R.string.flow_status_succeeded
            "CANCELLED" -> R.string.flow_status_cancelled
            else -> R.string.flow_status_failed
        },
    )

internal fun runStatusTone(status: String): StatusTone =
    when (status.uppercase()) {
        "RUNNING" -> StatusTone.RUNNING
        "PAUSED" -> StatusTone.PAUSED
        "SUCCEEDED" -> StatusTone.SUCCEEDED
        "CANCELLED" -> StatusTone.NEUTRAL
        else -> StatusTone.BLOCKED
    }

@Composable
internal fun nodeStatusLabel(status: NodeStatus): String =
    stringResource(
        when (status) {
            NodeStatus.OK -> R.string.flow_node_ok
            NodeStatus.FAILED -> R.string.flow_node_failed
            NodeStatus.SKIPPED -> R.string.flow_node_skipped
            NodeStatus.RECOVERED -> R.string.flow_node_recovered
        },
    )

internal fun nodeStatusTone(status: NodeStatus): StatusTone =
    when (status) {
        NodeStatus.OK -> StatusTone.SUCCEEDED
        NodeStatus.FAILED -> StatusTone.BLOCKED
        NodeStatus.SKIPPED -> StatusTone.NEUTRAL
        NodeStatus.RECOVERED -> StatusTone.WAITING
    }

/** 毫秒 → 简短时长（0.8 秒 / 12 秒 / 3 分 05 秒）。 */
@Composable
internal fun durationText(ms: Long?): String {
    if (ms == null) return stringResource(R.string.flow_duration_none)
    val seconds = ms / MS_PER_SECOND
    return when {
        ms < MS_PER_SECOND -> stringResource(R.string.flow_duration_ms, ms)
        seconds < SECONDS_PER_MINUTE -> stringResource(R.string.flow_duration_s, seconds)
        else ->
            stringResource(
                R.string.flow_duration_ms_min,
                seconds / SECONDS_PER_MINUTE,
                seconds % SECONDS_PER_MINUTE,
            )
    }
}

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

/** 参数输入框提示：名称 + 必填标记 + 说明 + 默认值。 */
@Composable
internal fun paramHint(p: FlowParam): String {
    val required = if (p.required) " ${stringResource(R.string.flow_param_required)}" else ""
    val note =
        p.description
            .takeIf { it.isNotBlank() }
            ?.let { " · $it" }
            .orEmpty()
    val default = p.default?.let { " · ${stringResource(R.string.flow_param_default, textOf(it))}" }.orEmpty()
    return "${p.name}$required$note$default"
}

/** 一组参数输入框。 */
@Composable
internal fun ParamFields(
    params: List<FlowParam>,
    values: Map<String, String>,
    onChange: (String, String) -> Unit,
) {
    val spacing = LocalSpacing.current
    params.forEach { p ->
        AutopInputBar(
            value = values[p.name].orEmpty(),
            onValueChange = { onChange(p.name, it) },
            spec =
                InputBarSpec(
                    placeholder = paramHint(p),
                    maxLines = if (p.type == ParamType.LIST || p.type == ParamType.OBJECT) PARAM_JSON_LINES else 3,
                    password = p.secret,
                ),
            modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge),
        )
    }
}

private const val PARAM_JSON_LINES = 5

/** 必填且没有默认值却没填的参数。 */
internal fun missingRequired(
    params: List<FlowParam>,
    values: Map<String, String>,
): Boolean = params.any { it.required && it.default == null && values[it.name].isNullOrBlank() }

/** 输入文本 → 按声明类型转 JSON（空值不写入）。 */
internal fun paramsJson(
    params: List<FlowParam>,
    values: Map<String, String>,
): JsonObject {
    val declared = params.associateBy { it.name }
    val out = LinkedHashMap<String, JsonElement>()
    for ((key, text) in values) {
        if (text.isBlank()) continue
        out[key] = convert(declared[key]?.type, text)
    }
    return JsonObject(out)
}

private fun convert(
    type: ParamType?,
    text: String,
): JsonElement =
    when (type) {
        ParamType.NUMBER, ParamType.TIME ->
            text.trim().toDoubleOrNull()?.let { JsonPrimitive(it) }
                ?: JsonPrimitive(text)
        ParamType.BOOLEAN -> JsonPrimitive(text.trim().lowercase() in TRUE_WORDS)
        ParamType.LIST, ParamType.OBJECT, ParamType.ANY ->
            runCatching { Json.parseToJsonElement(text) }.getOrDefault(JsonPrimitive(text))
        else -> JsonPrimitive(text)
    }

private val TRUE_WORDS = setOf("true", "是", "1")

/** 值 → 展示文本。 */
internal fun textOf(element: JsonElement): String = (element as? JsonPrimitive)?.content ?: element.toString()
