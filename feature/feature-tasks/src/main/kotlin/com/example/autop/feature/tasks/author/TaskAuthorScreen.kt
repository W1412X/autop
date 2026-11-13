/**
 * AI 创建任务页（与流程 AI 创建一致的交互）：用户消息为气泡，助手为 Markdown，提问为选项卡片
 * （选项 + 固定的"自己输入"一行），草稿为任务摘要卡片，底部输入栏始终可用。
 */
@file:Suppress("LongMethod") // Compose 页面：布局声明

package com.example.autop.feature.tasks.author

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopChip
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopModelSelector
import com.example.autop.core.ui.component.AutopOptionRow
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.MarkdownText
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.tasks.R
import com.example.autop.feature.tasks.sourceLabel
import com.example.autop.flow.AuthorLive
import com.example.autop.flow.AuthorMessage
import com.example.autop.task.TaskDraft

/** 跟随底部时一次滚动的像素数：远大于任何单次增量，实际滚动被列表末尾截断。 */
private const val FOLLOW_SCROLL_PX: Float = 1_000_000f

/** 用户气泡最大宽度占屏幕的比例（与对话页一致）。 */
private const val USER_BUBBLE_WIDTH_FRACTION: Float = 0.8f

/** 首页给出的示例提示语（点击填入输入框）。 */
private val EXAMPLE_KEYS: List<Int> =
    listOf(
        R.string.task_author_example_remind,
        R.string.task_author_example_condition,
        R.string.task_author_example_flow,
    )

/** AI 创建任务页。 */
@Suppress("CyclomaticComplexMethod") // 页面布局：各状态（空态、提问、草稿、错误）各自一个分支
@Composable
public fun TaskAuthorScreen(
    state: TaskAuthorUiState,
    onAction: (TaskAuthorAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    val author = state.author
    val listState = rememberLazyListState()
    // 是否跟随底部：流式输出时用户停在底部才跟随；手指一拖动就停止，滑回底部或发出新消息后恢复。
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(author.messages.size) { follow = true }
    LaunchedEffect(author.messages.size, author.live?.text?.length, author.live?.thinking?.length, follow) {
        if (follow && (author.messages.isNotEmpty() || author.live != null)) listState.scrollBy(FOLLOW_SCROLL_PX)
    }
    // 草稿出现或更新：只定位到草稿卡片顶部一次，之后不干预滚动。
    LaunchedEffect(author.draft) {
        if (author.draft != null) {
            follow = false
            val intro = if (!author.editing && author.messages.isEmpty() && author.live == null) 1 else 0
            listState.animateScrollToItem(intro + author.messages.size)
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
    ) {
        SubPageTopBar(
            title = stringResource(if (author.editing) R.string.task_author_title_edit else R.string.task_author_title),
            onBack = { onAction(TaskAuthorAction.Back) },
            actions = {
                AutopTonalButton(
                    text =
                        stringResource(
                            if (author.editing) R.string.task_author_save_edit else R.string.task_author_save,
                        ),
                    onClick = { onAction(TaskAuthorAction.Save) },
                    enabled = author.draft != null && !author.busy,
                )
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            contentPadding = PaddingValues(vertical = spacing.md),
        ) {
            if (author.messages.isEmpty() && author.live == null && !author.editing) {
                item { Intro(onAction, flowsEnabled = state.flowsEnabled) }
            }
            items(author.messages) { message ->
                MessageItem(message = message, pending = author.pendingQuestion, onAction = onAction)
            }
            // 草稿是此前轮次的产物，排在正在流式输出的这一轮之前，跟随底部时看到的始终是最新内容。
            author.draft?.let { draft -> item { DraftCard(draft = draft, flowsEnabled = state.flowsEnabled) } }
            author.live?.let { live -> item { LiveTurn(live = live) } }
            if (author.busy && author.live == null && author.pendingQuestion == null) {
                item {
                    Text(
                        text = stringResource(R.string.task_author_thinking),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = spacing.screenEdge),
                    )
                }
            }
            author.errorCode?.let { item { ErrorBanner(it) } }
            state.saveError?.let { item { ErrorBanner(it) } }
        }
        AutopModelSelector(
            selectedId = state.modelProfileId,
            profiles = state.modelOptions,
            enabled = !state.author.busy,
            onSelect = { onAction(TaskAuthorAction.SetModel(it)) },
        )
        AutopInputBar(
            value = state.input,
            onValueChange = { onAction(TaskAuthorAction.SetInput(it)) },
            spec =
                InputBarSpec(
                    placeholder =
                        stringResource(
                            if (author.pendingQuestion != null) {
                                R.string.task_author_answer_hint
                            } else {
                                R.string.task_author_input_hint
                            },
                        ),
                    maxLines = 4,
                ),
            modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge),
            trailing = {
                if (author.busy && author.pendingQuestion == null) {
                    AutopIconButton(
                        AutopIcons.Stop,
                        stringResource(R.string.task_author_stop),
                        { onAction(TaskAuthorAction.Stop) },
                    )
                } else {
                    AutopIconButton(
                        AutopIcons.Send,
                        stringResource(R.string.task_author_send),
                        { onAction(TaskAuthorAction.Send) },
                    )
                }
            },
        )
    }
}

@Composable
private fun Intro(
    onAction: (TaskAuthorAction) -> Unit,
    flowsEnabled: Boolean,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.padding(horizontal = spacing.screenEdge),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.task_author_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.task_author_examples_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 流程示例只在流程功能开启时展示（ADR-0030）。
        EXAMPLE_KEYS.filter { flowsEnabled || it != R.string.task_author_example_flow }.forEach { key ->
            val example = stringResource(key)
            AutopChip(
                text = example,
                selected = false,
                onClick = { onAction(TaskAuthorAction.SetInput(example)) },
            )
        }
    }
}

@Composable
private fun ErrorBanner(code: String) {
    StatusBanner(
        tone = StatusTone.BLOCKED,
        message = code,
        modifier = Modifier.fillMaxWidth().padding(LocalSpacing.current.screenEdge),
    )
}

@Composable
private fun MessageItem(
    message: AuthorMessage,
    pending: AuthorMessage.Question?,
    onAction: (TaskAuthorAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    when (message) {
        is AuthorMessage.User -> UserBubble(message.text)
        is AuthorMessage.Assistant ->
            MarkdownText(text = message.text, modifier = Modifier.padding(horizontal = spacing.screenEdge))
        is AuthorMessage.Thinking -> ThinkingBlock(text = message.text, streaming = false)
        is AuthorMessage.Activity ->
            Text(
                text = activityText(message.text),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.screenEdge),
            )
        is AuthorMessage.Question -> QuestionCard(message, pending = message === pending, onAction = onAction)
    }
}

/** 工具活动的展示文案（未知工具名原样显示）。 */
@Composable
private fun activityText(name: String): String =
    when (name) {
        "ask_user" -> stringResource(R.string.task_author_activity_ask_user)
        "inspect_screen" -> stringResource(R.string.task_author_activity_inspect_screen)
        "memory_search" -> stringResource(R.string.task_author_activity_memory_search)
        "list_flows" -> stringResource(R.string.task_author_activity_list_flows)
        "propose_task" -> stringResource(R.string.task_author_activity_propose_task)
        else -> name
    }

/** 用户消息：右对齐气泡，宽度不超过屏幕 80%（与对话页一致）。 */
@Composable
private fun UserBubble(text: String) {
    val spacing = LocalSpacing.current
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp * USER_BUBBLE_WIDTH_FRACTION
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier =
                Modifier
                    .widthIn(max = maxWidth)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
        )
    }
}

/**
 * 提问卡片：问题 + 每个选项一行 + 固定的"自己输入"一行（与流程 AI 创建的提问卡一致）。
 * 回答后只保留问题文字，回答作为用户气泡出现在对话里。
 */
@Composable
private fun QuestionCard(
    question: AuthorMessage.Question,
    pending: Boolean,
    onAction: (TaskAuthorAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenEdge)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(text = question.question, style = MaterialTheme.typography.bodyMedium)
        if (pending) {
            question.options.forEach { option ->
                AutopOptionRow(text = option, onClick = { onAction(TaskAuthorAction.Answer(option)) })
            }
            CustomAnswer(onAnswer = { onAction(TaskAuthorAction.Answer(it)) })
        }
    }
}

/** 固定的"自己输入"：收起时是一行选项，点开后是输入框，发送即作为回答。 */
@Composable
private fun CustomAnswer(onAnswer: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    if (!open) {
        AutopOptionRow(text = stringResource(R.string.task_author_custom_option), onClick = { open = true })
    } else {
        AutopInputBar(
            value = text,
            onValueChange = { text = it },
            spec = InputBarSpec(placeholder = stringResource(R.string.task_author_custom_hint), maxLines = 3),
            modifier = Modifier.fillMaxWidth(),
            trailing = {
                AutopIconButton(
                    AutopIcons.Send,
                    stringResource(R.string.task_author_send),
                    {
                        val answer = text.trim()
                        if (answer.isNotEmpty()) {
                            onAnswer(answer)
                            text = ""
                            open = false
                        }
                    },
                )
            },
        )
    }
}

/** 流式生成中的这一轮：思考展开实时显示，正文逐字出现，工具调用显示动作名。 */
@Composable
private fun LiveTurn(live: AuthorLive) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.padding(horizontal = spacing.screenEdge)) {
        if (live.thinking.isNotBlank()) {
            ThinkingBlock(text = live.thinking, streaming = live.text.isEmpty() && live.tool == null)
        }
        if (live.text.isNotBlank()) MarkdownText(text = live.text)
        live.tool?.let {
            Text(
                text = activityText(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (live.thinking.isBlank() && live.text.isBlank() && live.tool == null) {
            Text(
                text = stringResource(R.string.task_author_thinking),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 思考块：思考中默认展开，落定后折叠为「思考 · n 字」，点击切换（与对话页一致）。 */
@Composable
private fun ThinkingBlock(
    text: String,
    streaming: Boolean,
) {
    var manual by remember { mutableStateOf<Boolean?>(null) }
    val expanded = manual ?: streaming
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { manual = !expanded }
                .padding(horizontal = LocalSpacing.current.screenEdge, vertical = LocalSpacing.current.xs),
    ) {
        Text(
            text = stringResource(R.string.task_author_thinking_label, text.length),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            Text(
                text = text + if (streaming) " ▍" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 草稿卡片：名称、目标、触发方式、执行方式与提示。 */
@Composable
private fun DraftCard(
    draft: TaskDraft,
    flowsEnabled: Boolean,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenEdge)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        SectionTitle(text = stringResource(R.string.task_author_draft_title, draft.name))
        if (draft.goal.isNotBlank()) {
            Text(
                text = draft.goal,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = triggerText(draft), style = MaterialTheme.typography.bodySmall)
        Text(
            text =
                if (!flowsEnabled || draft.executorFlow.isNullOrBlank()) {
                    stringResource(R.string.task_author_executor_agent)
                } else {
                    stringResource(R.string.task_author_executor_flow, draft.executorFlow.orEmpty())
                },
            style = MaterialTheme.typography.bodySmall,
        )
        if (draft.hint.isNotBlank()) {
            Text(
                text = stringResource(R.string.task_author_hint, draft.hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 触发行：方式 + 时间/表达式/条件。 */
@Composable
private fun triggerText(draft: TaskDraft): String =
    when (draft.triggerType) {
        "manual" -> stringResource(R.string.task_author_trigger_manual)
        "once" -> stringResource(R.string.task_author_trigger_once, draft.at.orEmpty())
        "cron" -> stringResource(R.string.task_author_trigger_cron, draft.cron.orEmpty())
        "condition" ->
            stringResource(
                R.string.task_author_trigger_condition,
                sourceLabel(draft.source.orEmpty()),
                draft.condition.orEmpty(),
            )
        else -> draft.triggerType
    }
