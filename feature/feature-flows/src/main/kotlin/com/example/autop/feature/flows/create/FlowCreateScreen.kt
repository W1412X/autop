/**
 * 流程创建入口：AI 创建（主入口）、手动创建、导入（粘贴导出的 JSON）。
 */
package com.example.autop.feature.flows.create

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.NavRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.flows.R

/** 创建入口页。 */
@Composable
public fun FlowCreateScreen(
    state: FlowCreateUiState,
    onAction: (FlowCreateAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        SubPageTopBar(title = stringResource(R.string.flow_create_title), onBack = { onAction(FlowCreateAction.Back) })
        NavRow(
            title = stringResource(R.string.flow_create_ai),
            subtitle = stringResource(R.string.flow_create_ai_hint),
            onClick = { onAction(FlowCreateAction.StartAi) },
        )
        SectionTitle(text = stringResource(R.string.flow_create_manual))
        AutopInputBar(
            value = state.name,
            onValueChange = { onAction(FlowCreateAction.SetName(it)) },
            spec = InputBarSpec(placeholder = stringResource(R.string.flow_create_name)),
            modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge),
        )
        AutopButton(
            text = stringResource(R.string.flow_create_manual_go),
            onClick = { onAction(FlowCreateAction.CreateManual) },
            enabled = state.name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
        )
        SectionTitle(text = stringResource(R.string.flow_create_import))
        AutopInputBar(
            value = state.importText,
            onValueChange = { onAction(FlowCreateAction.SetImportText(it)) },
            spec = InputBarSpec(placeholder = stringResource(R.string.flow_create_import_hint), maxLines = 6),
            modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge),
        )
        AutopButton(
            text = stringResource(R.string.flow_create_import_go),
            onClick = { onAction(FlowCreateAction.Import) },
            enabled = state.importText.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
        )
        state.message?.let { msg ->
            StatusBanner(
                tone = if (state.messageIsError) StatusTone.BLOCKED else StatusTone.SUCCEEDED,
                message = importMessage(msg),
                modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge),
            )
        }
        if (state.missingEndpoints.isNotEmpty()) {
            NavRow(
                title =
                    stringResource(
                        R.string.flow_create_missing_endpoints,
                        state.missingEndpoints.joinToString("、"),
                    ),
                subtitle = stringResource(R.string.flow_create_missing_endpoints_hint),
                onClick = { onAction(FlowCreateAction.OpenEndpoints) },
            )
        }
    }
}

@Composable
private fun importMessage(msg: CreateMessage): String =
    when (msg) {
        is CreateMessage.Imported ->
            stringResource(
                R.string.flow_create_imported,
                msg.names.joinToString("、"),
                msg.skipped,
            )
        is CreateMessage.Failed -> stringResource(R.string.flow_create_failed, msg.reason)
    }
