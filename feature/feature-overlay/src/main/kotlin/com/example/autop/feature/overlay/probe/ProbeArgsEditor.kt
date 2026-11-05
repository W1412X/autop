/**
 * 工具测试面板的参数编辑器（N207）：每个参数一块（名称/类型/必填 + 说明 + 示例 +
 * 类型化输入控件），从 [ProbeScreen] 拆出（detekt 文件函数数阈值）。
 */
package com.example.autop.feature.overlay.probe

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.autop.agent.ToolParamKind
import com.example.autop.agent.ToolProbeParam
import com.example.autop.agent.ToolProbeTarget

/** 嵌套参数（对象/数组）编辑框的行高范围（dp）。 */
private const val JSON_INPUT_MIN_HEIGHT: Int = 48
private const val JSON_INPUT_MAX_HEIGHT: Int = 96

/** 参数区（N207）：每个参数一键控件 + 说明 + 示例回填；重置回全部示例值。 */
@Composable
internal fun ArgsSection(
    selected: ToolProbeTarget?,
    values: Map<String, String>,
    callbacks: ProbeCallbacks,
) {
    Text(
        text = "参数（留空＝不传，* 为必填）",
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (selected == null || selected.params.isEmpty()) {
        Text(
            text = "该工具无参数",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    selected.params.forEach { param ->
        ParamField(
            param = param,
            value = values[param.name].orEmpty(),
            onValueChange = { callbacks.onArgChange(param.name, it) },
        )
    }
    TextButton(onClick = callbacks.onResetArgs) { Text("重置为示例值") }
}

/** 单个参数块：名称/类型/必填 + 说明 + 示例 + 输入控件。 */
@Composable
private fun ParamField(
    param: ToolProbeParam,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(param.name, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            if (param.required) {
                Text(" *", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = kindLabelOf(param.kind),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (param.example.isNotBlank()) {
                TextButton(onClick = { onValueChange(param.example) }) { Text("填示例") }
            }
        }
        if (param.description.isNotBlank()) {
            Text(
                text = param.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (param.example.isNotBlank()) {
            Text(
                text = "示例：" + param.example,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ParamInput(param, value, onValueChange)
    }
}

/** 按参数类型分派输入控件：枚举/布尔下拉、嵌套 JSON 文本、普通文本。 */
@Composable
private fun ParamInput(
    param: ToolProbeParam,
    value: String,
    onValueChange: (String) -> Unit,
) {
    when {
        param.kind == ToolParamKind.BOOLEAN ->
            ChoicePicker(current = value, options = BOOLEAN_OPTIONS, onSelect = onValueChange)
        param.enumValues.isNotEmpty() ->
            ChoicePicker(current = value, options = listOf("") + param.enumValues, onSelect = onValueChange)
        param.kind == ToolParamKind.OBJECT || param.kind == ToolParamKind.ARRAY ->
            JsonTextInput(value = value, onValueChange = onValueChange)
        else ->
            PlainTextInput(
                value = value,
                onValueChange = onValueChange,
                numeric = param.kind == ToolParamKind.INTEGER || param.kind == ToolParamKind.NUMBER,
            )
    }
}

/** 限定取值下拉（首项「（不填）」清空该参数）。 */
@Composable
private fun ChoicePicker(
    current: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (current.isEmpty()) "（不填）" else current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option.isEmpty()) "（不填）" else option) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** 普通文本输入（字符串/数字；数字键出数字键盘）。 */
@Composable
private fun PlainTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .padding(6.dp),
    )
}

/** 嵌套参数（对象/数组）的 JSON 文本输入（说明里的子字段提示对照着填）。 */
@Composable
private fun JsonTextInput(
    value: String,
    onValueChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = JSON_INPUT_MIN_HEIGHT.dp, max = JSON_INPUT_MAX_HEIGHT.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .padding(6.dp)
                .verticalScroll(rememberScrollState()),
    )
}

/** 参数类型中文标注。 */
private fun kindLabelOf(kind: ToolParamKind): String =
    when (kind) {
        ToolParamKind.STRING -> "字符串"
        ToolParamKind.INTEGER -> "整数"
        ToolParamKind.NUMBER -> "小数"
        ToolParamKind.BOOLEAN -> "布尔"
        ToolParamKind.OBJECT -> "对象（JSON）"
        ToolParamKind.ARRAY -> "数组（JSON）"
    }

/** 布尔参数的下拉选项（空=不传）。 */
private val BOOLEAN_OPTIONS: List<String> = listOf("", "true", "false")
