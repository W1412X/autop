/**
 * 检索向量页（docs/tech/08 §4.5 `me/embedding`）：提供方选择（记忆与对话历史共用）；
 * 更换后显示"重建记忆索引 / 重建对话索引"进度（ReindexUiState 非 null 时展示）。
 */
package com.example.autop.feature.settings.me.embedding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.InfoRow
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R
import kotlinx.coroutines.delay

/** 提供方 id（docs/tech/03 §7 EmbeddingConfig.provider）。 */
private const val PROVIDER_NONE: String = "none"
private const val PROVIDER_OPENAI: String = "openai"

/** 模型名输入防抖（逐键写配置太频繁）。 */
private const val MODEL_DEBOUNCE_MS: Long = 400

private sealed interface EmbeddingSheet {
    data object Provider : EmbeddingSheet

    data object Profile : EmbeddingSheet
}

/**
 * 检索向量页（无状态）。
 *
 * @param state 页面状态。
 * @param onBack 返回"我的"页。
 * @param onAction 用户操作回调。
 */
@Composable
public fun EmbeddingScreen(
    state: EmbeddingUiState,
    onBack: () -> Unit,
    onAction: (EmbeddingAction) -> Unit,
) {
    var sheet by remember { mutableStateOf<EmbeddingSheet?>(null) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.embedding_title), onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { ProviderSection(state = state, onAction = onAction, onOpenSheet = { sheet = it }) }
            item { ReindexSection(reindex = state.reindex) }
            item { ReindexActions(onAction = onAction) }
        }
    }
    val currentSheet = sheet
    if (currentSheet != null) {
        EmbeddingSheetContent(
            sheet = currentSheet,
            state = state,
            onSelect = { id ->
                when (currentSheet) {
                    EmbeddingSheet.Provider -> onAction(EmbeddingAction.SetProvider(id))
                    EmbeddingSheet.Profile -> onAction(EmbeddingAction.SetProfile(id.ifEmpty { null }))
                }
                sheet = null
            },
            onDismiss = { sheet = null },
        )
    }
}

@Composable
private fun providerLabel(provider: String): String =
    when (provider) {
        PROVIDER_NONE -> stringResource(R.string.embedding_provider_none)
        PROVIDER_OPENAI -> stringResource(R.string.embedding_provider_openai)
        else -> provider
    }

/** 提供方选择区（provider/profile/模型名/维度）。 */
@Composable
private fun ProviderSection(
    state: EmbeddingUiState,
    onAction: (EmbeddingAction) -> Unit,
    onOpenSheet: (EmbeddingSheet) -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.embedding_section_provider))
        OptionRow(
            title = stringResource(R.string.embedding_provider),
            subtitle = stringResource(R.string.embedding_shared_note),
            value = providerLabel(state.section.provider),
            onClick = { onOpenSheet(EmbeddingSheet.Provider) },
        )
        if (state.section.provider != PROVIDER_NONE) {
            OptionRow(
                title = stringResource(R.string.embedding_profile),
                subtitle = null,
                value = profileLabel(state),
                onClick = { onOpenSheet(EmbeddingSheet.Profile) },
            )
            ModelField(state = state, onAction = onAction)
            ValueInfoRow(state = state)
        }
    }
}

/** 手动重建入口（重建记忆/对话索引，WP10）。 */
@Composable
private fun ReindexActions(onAction: (EmbeddingAction) -> Unit) {
    Column {
        SectionTitle(text = stringResource(R.string.embedding_section_reindex))
        ActionRow(
            title = stringResource(R.string.embedding_reindex_memory),
            subtitle = stringResource(R.string.embedding_reindex_memory_note),
            onClick = { onAction(EmbeddingAction.ReindexMemory) },
        )
        ActionRow(
            title = stringResource(R.string.embedding_reindex_threads),
            subtitle = stringResource(R.string.embedding_reindex_threads_note),
            onClick = { onAction(EmbeddingAction.ReindexThreads) },
        )
    }
}

@Composable
private fun profileLabel(state: EmbeddingUiState): String {
    val id = state.section.profileId ?: return stringResource(R.string.embedding_profile_unselected)
    return state.profiles.firstOrNull { it.id == id }?.displayName ?: id
}

/** 向量模型名输入（防抖后写 `llm.embedding.model`，docs/tech/03 §7）。 */
@Composable
private fun ModelField(
    state: EmbeddingUiState,
    onAction: (EmbeddingAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    var text by remember { mutableStateOf(state.section.model.orEmpty()) }
    LaunchedEffect(text) {
        delay(MODEL_DEBOUNCE_MS)
        if (text != (state.section.model.orEmpty())) {
            onAction(EmbeddingAction.SetModel(text))
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.embedding_model),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AutopInputBar(
            value = text,
            onValueChange = { text = it },
            spec = InputBarSpec(placeholder = stringResource(R.string.embedding_model_hint)),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 向量维度信息行（0/null=未知，docs/tech/03 §7"未知时首次调用后记录"）。 */
@Composable
private fun ValueInfoRow(state: EmbeddingUiState) {
    InfoRow(
        title = stringResource(R.string.embedding_dimension),
        subtitle = null,
        value =
            state.section.dimension?.toString()
                ?: stringResource(R.string.embedding_dimension_unknown),
    )
}

/** 重建进度（docs/tech/08 §4.5"重建记忆索引 / 重建对话索引"进度）。 */
@Composable
private fun ReindexSection(reindex: ReindexUiState) {
    Column {
        val memory = reindex.memory
        if (memory != null) {
            ReindexRow(titleRes = R.string.embedding_reindex_memory, progress = memory)
        }
        val threads = reindex.threads
        if (threads != null) {
            ReindexRow(titleRes = R.string.embedding_reindex_threads, progress = threads)
        }
    }
}

@Composable
private fun ReindexRow(
    @StringRes titleRes: Int,
    progress: ReindexProgress,
) {
    ListRow(
        title = stringResource(titleRes),
        subtitle = stringResource(R.string.embedding_reindex_progress, progress.done, progress.total),
        progress = if (progress.total > 0) progress.done.toFloat() / progress.total else 0f,
    )
}

@Composable
private fun EmbeddingSheetContent(
    sheet: EmbeddingSheet,
    state: EmbeddingUiState,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (sheet) {
        EmbeddingSheet.Provider ->
            OptionSheet(
                title = stringResource(R.string.embedding_provider),
                options =
                    listOf(
                        OptionItem(id = PROVIDER_NONE, label = stringResource(R.string.embedding_provider_none)),
                        OptionItem(id = PROVIDER_OPENAI, label = stringResource(R.string.embedding_provider_openai)),
                    ),
                selectedId = state.section.provider,
                onSelect = onSelect,
                onDismiss = onDismiss,
            )
        EmbeddingSheet.Profile ->
            OptionSheet(
                title = stringResource(R.string.embedding_profile),
                options =
                    state.profiles.map { OptionItem(id = it.id, label = it.displayName) } +
                        OptionItem(id = "", label = stringResource(R.string.embedding_profile_unselected)),
                selectedId = state.section.profileId.orEmpty(),
                onSelect = onSelect,
                onDismiss = onDismiss,
            )
    }
}
