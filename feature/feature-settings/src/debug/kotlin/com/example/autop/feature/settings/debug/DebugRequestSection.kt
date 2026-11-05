/**
 * 调试页"查看上次请求"区（docs/tech/08 §4.6，WP8；WP10 扩展 live 视图与 token 分布）：
 * 请求规模/用量、token 分布估算、逐条消息 live 视图与 system 全文（mono、可选中）。
 */
package com.example.autop.feature.settings.debug

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.InfoRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R
import com.example.autop.llm.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 查看上次请求（docs/tech/08 §4.6，WP8）：本轮 system 文本（mono、可选中）与
 * 规模、用量，system 估算 token 供核对 L0 ≤ 1500（WP10 扩展 live 视图与 token 分布）。
 */
@Composable
internal fun RequestSection(
    state: DebugUiState,
    onAction: (DebugAction) -> Unit,
) {
    SectionTitle(text = stringResource(R.string.debug_request_title))
    val request = state.lastRequest
    ActionRow(
        title = stringResource(R.string.debug_request_show),
        subtitle = request?.let { stringResource(R.string.debug_request_model, it.modelId) },
        onClick = { onAction(DebugAction.ShowLastRequest) },
    )
    if (!state.showLastRequest) return
    if (request == null) {
        EmptyHint(textRes = R.string.debug_request_empty)
        return
    }
    RequestRows(request = request)
    TokenRows(parts = request.tokenParts)
    LiveRows(messages = request.liveMessages)
    Text(
        text = stringResource(R.string.debug_request_system),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = LocalSpacing.current.lg),
    )
    val clipboard = LocalClipboardManager.current
    SelectionContainer {
        Text(
            text = request.systemText,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = REQUEST_TEXT_MAX_HEIGHT)
                    .padding(horizontal = LocalSpacing.current.lg),
        )
    }
    Row(modifier = Modifier.padding(horizontal = LocalSpacing.current.lg)) {
        AutopTextButton(
            text = stringResource(R.string.debug_copy),
            onClick = { clipboard.setText(AnnotatedString(request.systemText)) },
        )
        AutopTextButton(
            text = stringResource(R.string.debug_request_hide),
            onClick = { onAction(DebugAction.HideLastRequest) },
        )
    }
}

@Composable
private fun RequestRows(request: DebugRequestView) {
    InfoRow(
        title = stringResource(R.string.debug_request_time),
        subtitle = null,
        value = formatRequestTime(request.capturedAt),
    )
    InfoRow(
        title = stringResource(R.string.debug_request_messages),
        subtitle = null,
        value = request.messageCount.toString(),
    )
    InfoRow(
        title = stringResource(R.string.debug_request_tools),
        subtitle = null,
        value = request.toolCount.toString(),
    )
    InfoRow(
        title = stringResource(R.string.debug_request_system_tokens),
        subtitle = null,
        value = request.systemTokens.toString(),
    )
    val inputTokens = request.inputTokens
    val outputTokens = request.outputTokens
    InfoRow(
        title = stringResource(R.string.debug_request_usage),
        subtitle = null,
        value =
            if (inputTokens == null || outputTokens == null) {
                stringResource(R.string.debug_request_no_usage)
            } else {
                stringResource(R.string.debug_request_usage_value, inputTokens, outputTokens)
            },
    )
}

/** token 分布（WP10）：system/消息/工具/合计估算。 */
@Composable
private fun TokenRows(parts: List<DebugTokenPart>) {
    if (parts.isEmpty()) return
    Text(
        text = stringResource(R.string.debug_request_token_dist),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = LocalSpacing.current.lg),
    )
    parts.forEach { part ->
        InfoRow(
            title = stringResource(tokenSectionName(part.section)),
            subtitle = null,
            value = part.tokens.toString(),
        )
    }
}

/** live 视图（WP10）：逐条消息的角色标签 + 摘要文本（mono、可选中）。 */
@Composable
private fun LiveRows(messages: List<DebugLiveMessage>) {
    if (messages.isEmpty()) return
    Text(
        text = stringResource(R.string.debug_request_live),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = LocalSpacing.current.lg),
    )
    messages.forEach { message ->
        Text(
            text = stringResource(roleName(message.role)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = LocalSpacing.current.lg),
        )
        SelectionContainer {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LocalSpacing.current.lg),
            )
        }
    }
}

/** token 分段 → 文案资源。 */
private fun tokenSectionName(section: DebugTokenSection): Int =
    when (section) {
        DebugTokenSection.SYSTEM -> R.string.debug_request_tokens_system
        DebugTokenSection.MESSAGES -> R.string.debug_request_tokens_messages
        DebugTokenSection.TOOLS -> R.string.debug_request_tokens_tools
        DebugTokenSection.TOTAL -> R.string.debug_request_tokens_total
    }

/** 角色 → 文案资源。 */
private fun roleName(role: Role): Int =
    when (role) {
        Role.USER -> R.string.debug_request_role_user
        Role.ASSISTANT -> R.string.debug_request_role_assistant
        Role.TOOL -> R.string.debug_request_role_tool
    }

private fun formatRequestTime(epochMs: Long): String =
    SimpleDateFormat(REQUEST_TIME_PATTERN, Locale.getDefault()).format(Date(epochMs))

private val REQUEST_TEXT_MAX_HEIGHT = 240.dp
private const val REQUEST_TIME_PATTERN: String = "yyyy-MM-dd HH:mm"
