/**
 * "我的"二级页（docs/tech/08 §4.5 两级结构）：七个分类各一页，页内按小分组放设置项。
 *
 * 分组内容复用 me/ 下的各分组组件。选择弹层的开关由 [MeSheetState] 持有，弹层由 [MeSheetHost] 渲染。
 * 从一级状态卡跳入时，[focus] 指定的行会滚动到可见并短暂高亮（[FocusHighlight]）。
 */
package com.example.autop.feature.settings.me

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.NavRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.ToastEffect
import com.example.autop.feature.settings.MeFocus
import com.example.autop.feature.settings.MeGroup
import com.example.autop.feature.settings.R
import com.example.autop.feature.settings.SettingsSection
import kotlinx.coroutines.delay

/** 从一级状态卡跳入时，定位行的高亮时长（纯视觉反馈，不是业务阈值）。 */
private const val FOCUS_HIGHLIGHT_MS: Long = 1200L

/** 导入文档的 MIME 类型（备份为 JSON）。 */
private val BACKUP_MIME_TYPES = arrayOf("application/json", "text/json", "*/*")

/**
 * "我的"二级页（无状态，docs/standards/coding-standards §8）。
 *
 * @param group 分类。
 * @param state 页面状态。
 * @param focus 进入时需要定位高亮的行，null 表示不定位。
 * @param onBack 返回一级页。
 * @param onAction 用户操作回调。
 */
@Composable
public fun MeGroupScreen(
    group: MeGroup,
    state: MeUiState,
    focus: MeFocus?,
    onBack: () -> Unit,
    onAction: (MeAction) -> Unit,
) {
    val sheets = rememberMeSheetState()
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onAction(MeAction.ImportData(it.toString())) }
        }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(groupTitleRes(group)), onBack = onBack)
        NoticeToasts(state = state, onAction = onAction)
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            GroupContent(
                group = group,
                state = state,
                focus = focus,
                sheets = sheets,
                onAction = onAction,
                onImport = { importLauncher.launch(BACKUP_MIME_TYPES) },
            )
        }
    }
    MeSheetHost(sheets = sheets, state = state, onAction = onAction)
}

/** 按分类分发到对应的分组内容。 */
@Composable
private fun GroupContent(
    group: MeGroup,
    state: MeUiState,
    focus: MeFocus?,
    sheets: MeSheetState,
    onAction: (MeAction) -> Unit,
    onImport: () -> Unit,
) {
    val onOpen: (SettingsSection) -> Unit = { onAction(MeAction.OpenSection(it)) }
    when (group) {
        MeGroup.MODEL -> ModelLinksSection(state = state, onOpen = onOpen)
        MeGroup.PERMISSIONS_RUNTIME ->
            PermissionsSection(
                state = state,
                focus = focus,
                onOpen = onOpen,
                sheets = sheets,
                onAction = onAction,
            )
        MeGroup.SECURITY -> SecuritySection(onOpen = onOpen)
        MeGroup.TASKS ->
            TasksReminderSection(
                state = state,
                onOpenSnoozeSheet = { sheets.snooze = true },
                onAction = onAction,
            )
        MeGroup.APPEARANCE ->
            AppearanceSection(
                appearance = state.appearance,
                onOpenSheet = { sheets.appearance = it },
                onAction = onAction,
            )
        MeGroup.ADVANCED ->
            AdvancedGroup(
                state = state,
                onOpen = onOpen,
                onAction = onAction,
                onImport = onImport,
            )
        MeGroup.ABOUT -> AboutSection(state = state, onAction = onAction)
    }
}

/**
 * 一次性提示（Toast）：导出导入结果、占位提示与更新检查结果。
 * 两类提示共用 [MeAction.DismissNotice]，它会同时清空两个状态。
 */
@Composable
private fun NoticeToasts(
    state: MeUiState,
    onAction: (MeAction) -> Unit,
) {
    ToastEffect(
        message = state.notice?.let { stringResource(noticeMessageRes(it)) },
        durationMs = state.bannerDurationMs,
        onConsumed = { onAction(MeAction.DismissNotice) },
    )
    ToastEffect(
        message = state.updateNotice?.let { stringResource(updateNoticeMessageRes(it)) },
        durationMs = state.bannerDurationMs,
        onConsumed = { onAction(MeAction.DismissNotice) },
    )
}

@StringRes
private fun updateNoticeMessageRes(kind: UpdateNotice): Int =
    when (kind) {
        UpdateNotice.UP_TO_DATE -> R.string.me_update_up_to_date
        UpdateNotice.INSTALL_PERMISSION -> R.string.me_update_install_permission
    }

@StringRes
private fun noticeMessageRes(kind: NoticeKind): Int =
    when (kind) {
        NoticeKind.PLACEHOLDER -> R.string.me_placeholder_soon
        NoticeKind.EXPORTED -> R.string.me_backup_exported
        NoticeKind.EXPORT_FAILED -> R.string.me_backup_export_failed
        NoticeKind.IMPORTED -> R.string.me_backup_imported
        NoticeKind.IMPORT_FAILED -> R.string.me_backup_import_failed
    }

/** 模型分组（docs/tech/08 §4.5）：模型配置、观测方式、检索向量，副标题显示当前值。 */
@Composable
private fun ModelLinksSection(
    state: MeUiState,
    onOpen: (SettingsSection) -> Unit,
) {
    Column {
        NavRow(
            title = stringResource(R.string.me_nav_models),
            subtitle = state.defaultModelName ?: stringResource(R.string.me_model_unassigned),
            onClick = { onOpen(SettingsSection.MODEL) },
        )
        NavRow(
            title = stringResource(R.string.me_nav_observe),
            subtitle = stringResource(R.string.me_sub_observe),
            onClick = { onOpen(SettingsSection.OBSERVE) },
        )
        NavRow(
            title = stringResource(R.string.me_nav_embedding),
            subtitle = embeddingProviderLabel(state.embeddingProviderId),
            onClick = { onOpen(SettingsSection.EMBEDDING) },
        )
    }
}

/** 向量提供方显示名（docs/tech/03 §7 EmbeddingConfig.provider）。 */
@Composable
private fun embeddingProviderLabel(providerId: String): String =
    when (providerId) {
        "local" -> stringResource(R.string.me_embedding_local)
        "none" -> stringResource(R.string.me_embedding_off)
        "openai" -> stringResource(R.string.me_embedding_openai)
        else -> providerId
    }

/** 安全分组（docs/tech/08 §4.5）：安全规则、屏蔽的应用、操作记录。 */
@Composable
private fun SecuritySection(onOpen: (SettingsSection) -> Unit) {
    Column {
        NavRow(
            title = stringResource(R.string.me_nav_rules),
            subtitle = null,
            onClick = { onOpen(SettingsSection.RULES) },
        )
        NavRow(
            title = stringResource(R.string.me_nav_blocked_apps),
            subtitle = null,
            onClick = { onOpen(SettingsSection.BLOCKED_APPS) },
        )
        NavRow(
            title = stringResource(R.string.me_nav_audit),
            subtitle = null,
            onClick = { onOpen(SettingsSection.AUDIT) },
        )
    }
}

/**
 * 高级分组（docs/tech/08 §4.5）：配置（高级配置、提示词）、诊断（调试、诊断日志、工具测试记录）、
 * Beta 功能（ADR-0030，并入高级）、数据（导出 / 导入）。
 * "工具测试记录"入口只在调试模式（`ui.debug.verboseLogging`）下显示（tool_optimize D20）。
 */
@Composable
private fun AdvancedGroup(
    state: MeUiState,
    onOpen: (SettingsSection) -> Unit,
    onAction: (MeAction) -> Unit,
    onImport: () -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.me_sub_section_config))
        NavRow(
            title = stringResource(R.string.me_nav_advanced_config),
            subtitle = null,
            onClick = { onOpen(SettingsSection.ADVANCED) },
        )
        NavRow(
            title = stringResource(R.string.me_nav_prompts),
            subtitle = null,
            onClick = { onOpen(SettingsSection.PROMPTS) },
        )
        SectionTitle(text = stringResource(R.string.me_sub_section_diagnostic))
        NavRow(
            title = stringResource(R.string.me_nav_debug),
            subtitle = null,
            onClick = { onOpen(SettingsSection.DEBUG) },
        )
        NavRow(
            title = stringResource(R.string.me_nav_logs),
            subtitle = null,
            onClick = { onOpen(SettingsSection.LOGS) },
        )
        if (state.probeEntryVisible) {
            NavRow(
                title = stringResource(R.string.me_nav_probe_history),
                subtitle = null,
                onClick = { onOpen(SettingsSection.PROBE) },
            )
        }
        BetaSection(state = state, onAction = onAction)
        SectionTitle(text = stringResource(R.string.me_sub_section_data))
        ActionRow(
            title = stringResource(R.string.me_backup_export),
            subtitle = null,
            onClick = { onAction(MeAction.ExportData) },
        )
        ActionRow(
            title = stringResource(R.string.me_backup_import),
            subtitle = null,
            onClick = onImport,
        )
    }
}

/**
 * 从一级状态卡跳入时定位并高亮 [focused] 的行：先滚动到可见，再短暂高亮后恢复。
 */
@Composable
internal fun FocusHighlight(
    focused: Boolean,
    content: @Composable () -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    var highlighted by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused) {
            highlighted = true
            requester.bringIntoView()
            delay(FOCUS_HIGHLIGHT_MS)
            highlighted = false
        }
    }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(requester)
                .background(
                    color =
                        if (highlighted) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                ),
    ) {
        content()
    }
}
