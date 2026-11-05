/**
 * 操作记录页（docs/tech/08 §4.5"操作记录"）：工具调用的策略决策列表
 * （工具/目标/决策/规则/结果/时间），分页"加载更多"。
 */
package com.example.autop.feature.settings.me.audit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.subtitleParts
import com.example.autop.feature.settings.R
import com.example.autop.task.AuditEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 决策常量（与 task-impl `AuditStore` 的写入值一致）。 */
private const val DECISION_ALLOWED = "allowed"
private const val DECISION_BLOCKED = "blocked"

/**
 * 操作记录页（无状态，docs/standards/coding-standards §8）。
 *
 * @param state 页面状态。
 * @param onBack 返回。
 * @param onAction 用户操作回调。
 */
@Composable
public fun AuditScreen(
    state: AuditUiState,
    onBack: () -> Unit,
    onAction: (AuditAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.me_nav_audit), onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.entries) { entry ->
                AuditRow(entry = entry)
            }
            if (state.entries.isEmpty() && !state.loading) {
                item { EmptyState(text = stringResource(R.string.audit_empty)) }
            }
            if (!state.endReached && state.entries.isNotEmpty()) {
                item {
                    ActionRow(
                        title = stringResource(R.string.audit_load_more),
                        subtitle = null,
                        onClick = { onAction(AuditAction.LoadMore) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuditRow(entry: AuditEntry) {
    ListRow(
        title = entry.toolId,
        subtitle =
            subtitleParts(
                TIME_FORMAT.format(Date(entry.createdAt)),
                entry.targetSummary,
                decisionLabel(entry.decision),
                entry.ruleId?.let { stringResource(R.string.audit_rule, it) },
                resultLabel(entry.ok),
            ),
    )
}

@Composable
private fun decisionLabel(decision: String): String =
    when (decision) {
        DECISION_ALLOWED -> stringResource(R.string.audit_decision_allowed)
        DECISION_BLOCKED -> stringResource(R.string.audit_decision_blocked)
        else -> decision
    }

@Composable
private fun resultLabel(ok: Boolean?): String? =
    when (ok) {
        true -> stringResource(R.string.audit_result_ok)
        false -> stringResource(R.string.audit_result_failed)
        null -> null
    }

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
