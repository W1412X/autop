/**
 * 自定义规则编辑表单（docs/tech/08 §4.5 me/rules"用户规则增删"）：
 * 底部弹层，字段按规则类型（docs/tech/06 §6）切换。
 */
package com.example.autop.feature.settings.me.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopChip
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R

/** 多值字段的最大行数（每行一个值）。 */
private const val LINES_FIELD_MAX: Int = 4

/**
 * 规则编辑表单（无状态）。
 *
 * @param draft 表单草稿。
 * @param error 校验错误；null 表示无。
 * @param onAction 用户操作回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleEditorSheet(
    draft: RuleDraft,
    error: RulesError?,
    onAction: (RulesAction) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = { onAction(RulesAction.CancelEdit) }) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LocalSpacing.current.lg),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
        ) {
            Text(
                text =
                    if (draft.originalId == null) {
                        stringResource(R.string.rules_add)
                    } else {
                        stringResource(R.string.rules_edit_cd, draft.originalId)
                    },
                style = MaterialTheme.typography.titleSmall,
            )
            SingleField(
                label = stringResource(R.string.rules_field_id),
                value = draft.id,
                placeholder = stringResource(R.string.rules_field_id_hint),
            ) { onAction(RulesAction.SetField(draft.copy(id = it))) }
            ChipPicker(
                label = stringResource(R.string.rules_field_type),
                options = RULE_TYPES.map { it to ruleTypeLabel(it) },
                selected = draft.type,
            ) { onAction(RulesAction.SetField(draft.copy(type = it))) }
            ChipPicker(
                label = stringResource(R.string.rules_field_category),
                options = RULE_CATEGORIES.map { it to ruleCategoryLabel(it) },
                selected = draft.category,
            ) { onAction(RulesAction.SetField(draft.copy(category = it))) }
            ChipPicker(
                label = stringResource(R.string.rules_field_scope),
                options = RULE_SCOPES.map { it to ruleScopeLabel(it) },
                selected = draft.scope,
            ) { onAction(RulesAction.SetField(draft.copy(scope = it))) }
            ParamsFields(draft = draft, onAction = onAction)
            SwitchRow(
                title = stringResource(R.string.rules_field_enabled),
                subtitle = null,
                checked = draft.enabled,
                onCheckedChange = { onAction(RulesAction.SetField(draft.copy(enabled = it))) },
            )
            if (error != null) {
                ErrorText(error = error)
            }
            EditorButtonsRow(onAction = onAction)
        }
    }
}

/** 校验错误提示。 */
@Composable
private fun ErrorText(error: RulesError) {
    Text(
        text = rulesErrorText(error),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** 保存 / 取消按钮行。 */
@Composable
private fun EditorButtonsRow(onAction: (RulesAction) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        AutopButton(
            text = stringResource(R.string.rules_save),
            onClick = { onAction(RulesAction.SaveRule) },
        )
        AutopTextButton(
            text = stringResource(R.string.rules_cancel),
            onClick = { onAction(RulesAction.CancelEdit) },
        )
    }
}

/** 按规则类型显示的参数字段（docs/tech/06 §6 参数表）。 */
@Composable
private fun ParamsFields(
    draft: RuleDraft,
    onAction: (RulesAction) -> Unit,
) {
    when (draft.type) {
        "password_field" -> Unit
        "package" -> {
            LinesField(stringResource(R.string.rules_field_packages), draft.packages) {
                onAction(RulesAction.SetField(draft.copy(packages = it)))
            }
            LinesField(stringResource(R.string.rules_field_prefixes), draft.prefixes) {
                onAction(RulesAction.SetField(draft.copy(prefixes = it)))
            }
        }
        "activity_regex" -> ValuesField(draft, R.string.rules_field_patterns, onAction)
        "uri_scheme" -> ValuesField(draft, R.string.rules_field_schemes, onAction)
        else -> {
            ValuesField(draft, R.string.rules_field_values, onAction)
            if (draft.type == RuleDraft.TYPE_TEXT_ANY) {
                LinesField(stringResource(R.string.rules_field_packages), draft.packages) {
                    onAction(RulesAction.SetField(draft.copy(packages = it)))
                }
                SingleField(
                    label = stringResource(R.string.rules_field_min_hits),
                    value = draft.minHits,
                    placeholder = "",
                ) { onAction(RulesAction.SetField(draft.copy(minHits = it))) }
            }
        }
    }
}

@Composable
private fun ValuesField(
    draft: RuleDraft,
    labelRes: Int,
    onAction: (RulesAction) -> Unit,
) {
    LinesField(stringResource(labelRes), draft.values) { onAction(RulesAction.SetField(draft.copy(values = it))) }
}

@Composable
private fun LinesField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    Column {
        FieldLabel(label)
        AutopInputBar(
            value = value,
            onValueChange = onChange,
            spec = InputBarSpec(maxLines = LINES_FIELD_MAX),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SingleField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    Column {
        FieldLabel(label)
        AutopInputBar(
            value = value,
            onValueChange = onChange,
            spec = InputBarSpec(placeholder = placeholder),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 选项芯片行（type / category / scope）。 */
@Composable
private fun ChipPicker(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        FieldLabel(label)
        LazyRow(
            modifier = Modifier.heightIn(min = MIN_CHIP_ROW_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
        ) {
            items(options, key = { it.first }) { (id, text) ->
                AutopChip(text = text, selected = id == selected, onClick = { onSelect(id) })
            }
        }
    }
}

/** 芯片行最小高度（保证 48dp 触控区，docs/tech/08 §6）。 */
private val MIN_CHIP_ROW_HEIGHT = 48.dp
