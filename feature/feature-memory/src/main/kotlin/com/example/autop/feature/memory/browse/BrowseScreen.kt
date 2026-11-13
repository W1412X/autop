/**
 * 记忆浏览页（docs/tech/08 §4.4）：搜索（防抖切换单列结果）、面包屑、
 * 根目录“最近变更”入口、children 分页列表、新建条目/目录。
 */
package com.example.autop.feature.memory.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
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
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ErrorState
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.OptionIcon
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.PagedList
import com.example.autop.core.ui.component.PagedListState
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.component.TopLevelHeader
import com.example.autop.core.ui.component.subtitleParts
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.core.ui.theme.scaled
import com.example.autop.feature.memory.R

/** 浏览页。 */
@Composable
public fun BrowseScreen(
    state: BrowseUiState,
    onAction: (BrowseAction) -> Unit,
    onOpenNode: (String) -> Unit,
    onOpenNew: (parent: String, isDirectory: Boolean) -> Unit,
    onOpenChanges: () -> Unit,
) {
    var showCreateSheet by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        TopLevelHeader(
            title = stringResource(R.string.memory_browse_title),
            actions = {
                AutopIconButton(
                    icon = AutopIcons.Add,
                    contentDescription = stringResource(R.string.memory_create_new),
                    onClick = { showCreateSheet = true },
                )
            },
        )
        SearchField(query = state.query, onAction = onAction)
        if (state.query.isBlank()) {
            BrowseContent(
                state = state,
                onAction = onAction,
                onOpenNode = onOpenNode,
                onOpenChanges = onOpenChanges,
            )
        } else {
            SearchResults(state = state, onOpenNode = onOpenNode)
        }
    }

    if (showCreateSheet) {
        CreateTypeSheet(
            onSelect = { isDirectory ->
                showCreateSheet = false
                onOpenNew(state.path, isDirectory)
            },
            onDismiss = { showCreateSheet = false },
        )
    }
    if (state.pendingDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.memory_delete_title),
            text = stringResource(R.string.memory_delete_text),
            onConfirm = { onAction(BrowseAction.ConfirmDelete) },
            onDismiss = { onAction(BrowseAction.DismissDelete) },
            destructive = true,
        )
    }
}

/** 新建类型选择（条目 / 目录）；选定后直接进入编辑页，名称在编辑页内填写。 */
@Composable
private fun CreateTypeSheet(
    onSelect: (isDirectory: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionSheet(
        title = stringResource(R.string.memory_create_new),
        options =
            listOf(
                OptionItem(
                    id = CREATE_ITEM_ID,
                    label = stringResource(R.string.memory_create_item),
                    icon = OptionIcon.ADD,
                ),
                OptionItem(
                    id = CREATE_DIR_ID,
                    label = stringResource(R.string.memory_create_dir),
                    icon = OptionIcon.FOLDER,
                ),
            ),
        selectedId = null,
        onSelect = { id -> onSelect(id == CREATE_DIR_ID) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun BrowseContent(
    state: BrowseUiState,
    onAction: (BrowseAction) -> Unit,
    onOpenNode: (String) -> Unit,
    onOpenChanges: () -> Unit,
) {
    Breadcrumbs(crumbs = state.crumbs, onAction = onAction)
    if (state.path == "/") {
        state.recentChangesCount?.takeIf { it > 0 }?.let { count ->
            RecentChangesRow(count = count, onClick = onOpenChanges)
        }
    }
    when {
        state.error != null ->
            ErrorState(text = state.error.orEmpty(), onRetry = { onAction(BrowseAction.Retry) })
        state.loading -> EmptyState(text = stringResource(R.string.memory_loading))
        state.rows.isEmpty() ->
            EmptyState(text = stringResource(R.string.memory_empty_dir))
        else ->
            PagedList(
                items = state.rows,
                state = state.paged,
                onLoadMore = { onAction(BrowseAction.LoadMore) },
            ) { row ->
                ChildRow(row = row, onOpenNode = onOpenNode, onAction = onAction)
            }
    }
}

@Composable
private fun SearchField(
    query: String,
    onAction: (BrowseAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    AutopInputBar(
        value = query,
        onValueChange = { onAction(BrowseAction.QueryChanged(it)) },
        spec = InputBarSpec(placeholder = stringResource(R.string.memory_search_placeholder)),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.xs),
        leading = {
            Icon(
                imageVector = AutopIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.Sm.scaled()),
            )
        },
    )
}

/** 面包屑：只在进入子目录后出现，用文字路径而不是胶囊，当前层级加深显示。 */
@Composable
private fun Breadcrumbs(
    crumbs: List<String>,
    onAction: (BrowseAction) -> Unit,
) {
    if (crumbs.size <= 1) return
    val spacing = LocalSpacing.current
    LazyRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs) { index, crumb ->
            val current = index == crumbs.lastIndex
            if (index > 0) {
                Icon(
                    imageVector = AutopIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.Sm.scaled()),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = crumbSegmentLabel(crumb),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (current) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier =
                    Modifier
                        .clickable(enabled = !current) { onAction(BrowseAction.OpenCrumb(index)) }
                        .padding(vertical = spacing.sm, horizontal = spacing.xs),
            )
        }
    }
}

@Composable
private fun ChildRow(
    row: BrowseRow,
    onOpenNode: (String) -> Unit,
    onAction: (BrowseAction) -> Unit,
) {
    val subtitle =
        if (row.isDirectory && row.childCount > 0) {
            subtitleParts(row.summary, stringResource(R.string.memory_child_count, row.childCount))
        } else {
            row.summary
        }
    ListRow(
        title = row.title,
        subtitle = subtitle,
        leading = if (row.isDirectory) AutopIcons.Folder else AutopIcons.Description,
        trailing =
            if (row.byAgent) {
                {
                    StatusChip(text = stringResource(R.string.memory_agent_badge), tone = StatusTone.NEUTRAL)
                }
            } else {
                null
            },
        modifier =
            Modifier.combinedClickable(
                onClick = {
                    if (row.isDirectory) {
                        onAction(BrowseAction.OpenRow(row.path, isDirectory = true))
                    } else {
                        onOpenNode(row.path)
                    }
                },
                // 长按＝删除入口（弹确认，防误触；目录整树递归删）。
                onLongClick = { onAction(BrowseAction.RequestDelete(row.path)) },
            ),
    )
}

@Composable
private fun SearchResults(
    state: BrowseUiState,
    onOpenNode: (String) -> Unit,
) {
    when {
        state.searching -> EmptyState(text = stringResource(R.string.memory_searching))
        state.searchResults.isEmpty() -> EmptyState(text = stringResource(R.string.memory_search_empty))
        else ->
            PagedList(
                items = state.searchResults,
                state = PagedListState(endReached = true),
                onLoadMore = {},
            ) { hit ->
                ListRow(
                    title = hit.title,
                    subtitle = hit.snippet,
                    leading = AutopIcons.Description,
                    modifier = Modifier.clickable { onOpenNode(hit.path) },
                )
            }
    }
}

@Composable
private fun crumbSegmentLabel(crumb: String): String =
    if (crumb == "/") {
        stringResource(R.string.memory_root_label)
    } else {
        crumb.substringAfterLast('/')
    }

/** 「最近变更」入口：无底色的一行，左标题右计数 + chevron。 */
@Composable
private fun RecentChangesRow(
    count: Int,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.memory_recent_changes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.memory_recent_changes_hint, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = AutopIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(IconSize.Sm.scaled()),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val CREATE_ITEM_ID: String = "item"
private const val CREATE_DIR_ID: String = "dir"
