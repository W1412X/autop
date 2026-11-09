/**
 * "我的"页（docs/tech/08 §4.5）：一个 LazyColumn、SectionTitle 分组、组间 HorizontalDivider。
 *
 * 分组：健康状态卡 / 外观 / 模型 / 权限与运行 / 安全 / 任务与提醒 / 高级（含导出导入）/ 关于。
 * 高级配置、提示词、诊断日志、操作记录为子页入口（`me/{id}`）。
 */
package com.example.autop.feature.settings.me

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.InlineBanner
import com.example.autop.core.ui.component.NavRow
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.TopLevelHeader
import com.example.autop.feature.settings.R
import com.example.autop.feature.settings.SettingsSection

/**
 * "我的"页（无状态，docs/standards/coding-standards §8）。
 *
 * @param state 页面状态。
 * @param onAction 用户操作回调。
 */
@Composable
@Suppress("LongMethod") // “我的”页单屏分区渲染，拆小函数需透传大量状态
public fun MeScreen(
    state: MeUiState,
    onAction: (MeAction) -> Unit,
) {
    var sheet by remember { mutableStateOf<SheetKind?>(null) }
    var windowTypeSheet by remember { mutableStateOf(false) }
    var displayModeSheet by remember { mutableStateOf(false) }
    var opacitySheet by remember { mutableStateOf(false) }
    var snoozeSheet by remember { mutableStateOf(false) }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onAction(MeAction.ImportData(it.toString())) }
        }
    val onOpenSection: (SettingsSection) -> Unit = { onAction(MeAction.OpenSection(it)) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        NoticeBanner(state = state, onAction = onAction)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { TopLevelHeader(title = stringResource(R.string.me_title)) }
            item {
                HealthCard(
                    state = state,
                    onOpenHealth = { onOpenSection(SettingsSection.PERMISSIONS) },
                )
            }
            item {
                AppearanceSection(
                    appearance = state.appearance,
                    onOpenSheet = { kind -> sheet = kind },
                    onAction = onAction,
                )
            }
            item { ModelLinksSection(state = state, onOpen = onOpenSection) }
            item {
                HorizontalDivider()
                PermissionsSection(
                    state = state,
                    onOpen = onOpenSection,
                    onOpenWindowTypeSheet = { windowTypeSheet = true },
                    onOpenDisplayModeSheet = { displayModeSheet = true },
                    onOpenOpacitySheet = { opacitySheet = true },
                    onAction = onAction,
                )
            }
            item {
                HorizontalDivider()
                SecuritySection(onOpen = onOpenSection)
            }
            item {
                HorizontalDivider()
                TasksReminderSection(
                    state = state,
                    onOpenSnoozeSheet = { snoozeSheet = true },
                    onAction = onAction,
                )
            }
            item {
                HorizontalDivider()
                AdvancedSection(
                    onOpen = onOpenSection,
                    onExport = { onAction(MeAction.ExportData) },
                    onImport = { importLauncher.launch(BACKUP_MIME_TYPES) },
                )
            }
            item {
                HorizontalDivider()
                AboutSection()
            }
        }
    }
    val currentSheet = sheet
    AppearanceSheetHost(
        kind = currentSheet,
        appearance = state.appearance,
        onSelect = { id ->
            currentSheet?.let { onAction(it.toAction(id)) }
            sheet = null
        },
        onDismiss = { sheet = null },
    )
    if (windowTypeSheet) {
        WindowTypeSheet(
            selectedId = state.overlayWindowType,
            onSelect = { id ->
                onAction(MeAction.SetOverlayWindowType(id))
                windowTypeSheet = false
            },
            onDismiss = { windowTypeSheet = false },
        )
    }
    if (displayModeSheet) {
        DisplayModeSheet(
            selectedId = state.overlayDisplayMode,
            onSelect = { id ->
                onAction(MeAction.SetOverlayDisplayMode(id))
                displayModeSheet = false
            },
            onDismiss = { displayModeSheet = false },
        )
    }
    if (opacitySheet) {
        OpacitySheet(
            selected = state.overlayOpacity,
            onSelect = { value ->
                onAction(MeAction.SetOverlayOpacity(value))
                opacitySheet = false
            },
            onDismiss = { opacitySheet = false },
        )
    }
    if (snoozeSheet) {
        SnoozeSheet(
            options = state.snoozeOptions,
            selected = state.snoozeMinutes,
            onSelect = { minutes ->
                onAction(MeAction.SetSnoozeMinutes(minutes))
                snoozeSheet = false
            },
            onDismiss = { snoozeSheet = false },
        )
    }
}

/** 占位点击横幅（"即将推出"等提示，ui.banner.durationMs 后消失）。 */
@Composable
private fun NoticeBanner(
    state: MeUiState,
    onAction: (MeAction) -> Unit,
) {
    val kind = state.notice ?: return
    InlineBanner(
        message = stringResource(noticeMessageRes(kind)),
        onDismiss = { onAction(MeAction.DismissNotice) },
        durationMs = state.bannerDurationMs,
    )
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

/** 模型分组（docs/tech/08 §4.5）：模型配置（副标题=主模型名称）与检索向量（副标题=提供方）。 */
@Composable
private fun ModelLinksSection(
    state: MeUiState,
    onOpen: (SettingsSection) -> Unit,
) {
    Column {
        HorizontalDivider()
        SectionTitle(text = stringResource(R.string.me_section_model))
        NavRow(
            title = stringResource(R.string.me_nav_models),
            subtitle = state.mainModelName ?: stringResource(R.string.me_model_unassigned),
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
        "none" -> stringResource(R.string.me_embedding_off)
        "openai" -> stringResource(R.string.me_embedding_openai)
        else -> providerId
    }

/** 安全分组（docs/tech/08 §4.5）：安全规则、屏蔽的应用、节点操作、操作记录。 */
@Composable
private fun SecuritySection(onOpen: (SettingsSection) -> Unit) {
    Column {
        SectionTitle(text = stringResource(R.string.me_section_security))
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
            title = stringResource(R.string.me_nav_node_action),
            subtitle = stringResource(R.string.me_sub_node_action),
            onClick = { onOpen(SettingsSection.NODE_ACTION) },
        )
        NavRow(
            title = stringResource(R.string.me_nav_audit),
            subtitle = null,
            onClick = { onOpen(SettingsSection.AUDIT) },
        )
    }
}

/** 高级分组（docs/tech/08 §4.5）：高级配置 / 提示词 / 调试 / 诊断日志 + 导出/导入数据。 */
@Composable
private fun AdvancedSection(
    onOpen: (SettingsSection) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.me_section_advanced))
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
        NavRow(
            title = stringResource(R.string.me_nav_probe_history),
            subtitle = null,
            onClick = { onOpen(SettingsSection.PROBE) },
        )
        ActionRow(
            title = stringResource(R.string.me_backup_export),
            subtitle = null,
            onClick = onExport,
        )
        ActionRow(
            title = stringResource(R.string.me_backup_import),
            subtitle = null,
            onClick = onImport,
        )
    }
}

/** 默认稍后档位选择弹层（`task.reminder.snoozeOptions`）。 */
@Composable
private fun SnoozeSheet(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionSheet(
        title = stringResource(R.string.me_snooze_default),
        options =
            options.map { minutes ->
                OptionItem(
                    id = minutes.toString(),
                    label = stringResource(R.string.me_minutes, minutes),
                )
            },
        selectedId = selected.toString(),
        onSelect = { id -> id.toIntOrNull()?.let(onSelect) },
        onDismiss = onDismiss,
    )
}

/** 导入文档的 MIME 类型（备份为 JSON）。 */
private val BACKUP_MIME_TYPES = arrayOf("application/json", "text/json", "*/*")
