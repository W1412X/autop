/**
 * 安全规则页（docs/tech/08 §4.5 me/rules）：内置规则只读列表 + 用户规则增删。
 */
package com.example.autop.feature.settings.me.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R

/**
 * 安全规则页（无状态）。
 *
 * @param state 页面状态。
 * @param onBack 返回"我的"页。
 * @param onAction 用户操作回调。
 */
@Composable
public fun RulesScreen(
    state: RulesUiState,
    onBack: () -> Unit,
    onAction: (RulesAction) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Int?>(null) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.rules_title), onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { BuiltinSection(rows = state.builtin) }
            item {
                HorizontalDivider()
                SectionTitle(text = stringResource(R.string.rules_section_user))
            }
            itemsIndexed(state.user) { index, row ->
                UserRuleRow(
                    row = row,
                    onEdit = { row.id?.let { id -> onAction(RulesAction.EditRule(id)) } },
                    onDelete = { pendingDelete = index },
                )
            }
            item {
                if (state.user.isEmpty()) {
                    EmptyState(text = stringResource(R.string.rules_user_empty))
                }
                ActionRow(
                    title = stringResource(R.string.rules_add),
                    subtitle = null,
                    onClick = { onAction(RulesAction.AddRule) },
                )
            }
        }
    }
    state.editing?.let { draft ->
        RuleEditorSheet(draft = draft, error = state.error, onAction = onAction)
    }
    val deleting = pendingDelete
    if (deleting != null) {
        ConfirmDialog(
            title = stringResource(R.string.rules_delete_confirm_title),
            text = stringResource(R.string.rules_delete_confirm_text),
            onConfirm = {
                onAction(RulesAction.DeleteRule(deleting))
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
}

/** 内置规则（只读，docs/tech/08 §4.5"内置规则只读列表"）。 */
@Composable
private fun BuiltinSection(rows: List<RuleRow>) {
    Column {
        SectionTitle(text = stringResource(R.string.rules_section_builtin))
        Text(
            text = stringResource(R.string.rules_builtin_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = LocalSpacing.current.lg),
        )
        rows.forEach { row -> RuleRowContent(row = row, trailing = null) }
    }
}

/** 自定义规则行（点击编辑，trailing 删除）。 */
@Composable
private fun UserRuleRow(
    row: RuleRow,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    RuleRowContent(
        row = row,
        trailing = {
            AutopTextButton(
                text = stringResource(R.string.rules_delete_button),
                onClick = onDelete,
                destructive = true,
            )
        },
        onClick = onEdit,
    )
}

@Composable
private fun RuleRowContent(
    row: RuleRow,
    trailing: (@Composable () -> Unit)?,
    onClick: (() -> Unit)? = null,
) {
    val modifier =
        if (onClick != null) {
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ListRow(
            title = row.id ?: stringResource(R.string.rules_invalid_rule),
            subtitle = ruleSubtitle(row),
            modifier = Modifier.weight(1f),
            trailing = trailing,
        )
    }
}

@Composable
private fun ruleSubtitle(row: RuleRow): String {
    val type = row.type ?: return ""
    val parts =
        listOf(
            ruleTypeLabel(type),
            row.category?.let { ruleCategoryLabel(it) }.orEmpty(),
            row.scope?.let { ruleScopeLabel(it) }.orEmpty(),
        )
    return parts.filter { it.isNotEmpty() }.joinToString(" · ")
}
