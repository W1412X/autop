/**
 * AI 创建任务页（与流程 AI 创建一致的交互）：用户消息为气泡，助手为 Markdown，提问为选项卡片
 * （选项 + 固定的"自己输入"一行），草稿为任务摘要卡片，底部输入栏始终可用。
 */
@file:Suppress("LongMethod") // Compose 页面：布局声明

package com.example.autop.feature.tasks.author

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.common.RunMode
import com.example.autop.core.thread.ui.AttachImageButton
import com.example.autop.core.thread.ui.PendingImageStrip
import com.example.autop.core.thread.ui.SentImageRow
import com.example.autop.core.ui.component.AutopChip
import com.example.autop.core.ui.component.AutopComposer
import com.example.autop.core.ui.component.AutopModelSelector
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.AwaitingReplyLine
import com.example.autop.core.ui.component.ComposerTrailing
import com.example.autop.core.ui.component.InlineQuestionCard
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.MarkdownText
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.VoiceErrorToast
import com.example.autop.core.ui.component.ballface.AutopBallFace
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.component.llmErrorTextRes
import com.example.autop.core.ui.component.rememberStickToBottom
import com.example.autop.core.ui.component.rememberVoiceStarter
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.tasks.R
import com.example.autop.feature.tasks.sourceLabel
import com.example.autop.flow.AuthorLive
import com.example.autop.flow.AuthorMessage
import com.example.autop.task.TaskDraft

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
    val startVoice =
        rememberVoiceStarter(
            onGranted = { onAction(TaskAuthorAction.StartVoice) },
            onDenied = { onAction(TaskAuthorAction.VoiceFailed("speech.permission")) },
        )
    val spacing = LocalSpacing.current
    val author = state.author
    val listState = rememberLazyListState()
    val stick = rememberStickToBottom(listState)
    // 用户发出消息后无条件回到最新（即便此前上滑）；其余增长只在用户停在底部时跟随。
    LaunchedEffect(author.messages.size) {
        if (author.messages.lastOrNull() is AuthorMessage.User) stick.follow()
    }
    // 打开页面时就有的草稿（编辑既有任务/运行中回到未结束会话）：直接贴底。
    // 恢复的状态可能晚于首帧到达（编辑既有任务要读库），此时以"草稿不是运行中产出的"为准。
    // 会话中后续产出的草稿仍定位到草稿卡顶部一次，之后不干预滚动。
    val restoredDraft = remember { author.draft }
    var draftHandled by remember { mutableStateOf(false) }
    LaunchedEffect(author.draft) {
        val draft = author.draft ?: return@LaunchedEffect
        if (!draftHandled && (draft == restoredDraft || !author.busy)) {
            draftHandled = true
            stick.follow()
            return@LaunchedEffect
        }
        draftHandled = true
        stick.release()
        val intro = if (!author.editing && author.messages.isEmpty() && author.live == null) 1 else 0
        listState.animateScrollToItem(intro + author.messages.size)
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
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
            contentPadding = PaddingValues(horizontal = spacing.screenEdge, vertical = spacing.md),
        ) {
            if (author.messages.isEmpty() && author.live == null && !author.editing) {
                item { Intro(onAction, flowsEnabled = state.flowsEnabled) }
            }
            items(author.messages) { message ->
                MessageItem(message = message, pending = author.pendingQuestion, onAction = onAction)
            }
            // 草稿是此前轮次的产物，排在正在流式输出的这一轮之前，跟随底部时看到的始终是最新内容。
            author.draft?.let { draft ->
                item {
                    DraftCard(
                        draft = draft,
                        flowsEnabled = state.flowsEnabled,
                        issues = author.issues,
                        warnings = author.warnings,
                    )
                }
            }
            author.live?.let { live -> item { LiveTurn(live = live) } }
            if (author.busy && author.live == null && author.pendingQuestion == null) {
                item {
                    AwaitingReplyLine(
                        text = stringResource(R.string.task_author_thinking),
                        faceSize = AUTHOR_FACE_SIZE,
                        textStyle = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            author.errorCode?.let { item { ErrorBanner(it) } }
            state.saveError?.let { item { ErrorBanner(it) } }
            if (state.imageError) item { ErrorBanner(AUTHOR_IMAGE_FAILED) }
        }
        VoiceErrorToast(code = state.voiceError)
        LeaveGuard(state = state, onAction = onAction)
        if (state.images.isNotEmpty()) {
            PendingImageStrip(
                images = state.images,
                onRemove = { onAction(TaskAuthorAction.RemoveImage(it)) },
                modifier = Modifier.padding(horizontal = spacing.screenEdge),
            )
        }
        AutopComposer(
            value = state.input,
            onValueChange = { onAction(TaskAuthorAction.SetInput(it)) },
            leading = {
                AttachImageButton(
                    onImages = { onAction(TaskAuthorAction.AddImages(it)) },
                    onCaptureFailed = { onAction(TaskAuthorAction.ImageFailed) },
                )
            },
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
            top = {
                AutopModelSelector(
                    selectedId = state.modelProfileId,
                    profiles = state.modelOptions,
                    enabled = !state.author.busy,
                    onSelect = { onAction(TaskAuthorAction.SetModel(it)) },
                    defaultProfileId = state.defaultModelId,
                )
            },
            trailing =
                when {
                    state.listening -> ComposerTrailing.VOICE
                    author.busy && author.pendingQuestion == null -> ComposerTrailing.STOP
                    state.voiceAvailable && state.input.isBlank() -> ComposerTrailing.VOICE
                    else -> ComposerTrailing.SEND
                },
            listening = state.listening,
            voiceLevel = state.voiceLevel,
            onVoice = { if (state.listening) onAction(TaskAuthorAction.StopVoice) else startVoice() },
            onSend = { onAction(TaskAuthorAction.Send) },
            onStop = { onAction(TaskAuthorAction.Stop) },
        )
    }
}

@Composable
private fun Intro(
    onAction: (TaskAuthorAction) -> Unit,
    flowsEnabled: Boolean,
) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
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
private fun authorErrorText(code: String): String =
    when (code) {
        AUTHOR_LLM_UNAVAILABLE -> stringResource(R.string.task_author_llm_unavailable)
        AUTHOR_IMAGE_FAILED -> stringResource(R.string.task_author_image_failed)
        else -> stringResource(llmErrorTextRes(code))
    }

/** 任务创建页自身的错误码（TaskAuthorImpl.ERROR_LLM_UNAVAILABLE）。 */
private const val AUTHOR_LLM_UNAVAILABLE: String = "task.author_llm_unavailable"

/** 图片读取或拍照失败的错误码（界面内部使用，不进入 LLM 错误）。 */
private const val AUTHOR_IMAGE_FAILED: String = "task.author_image_failed"

/** 创建页表情尺寸（P1-14：生成/提问/草稿三处 20dp）。 */
private val AUTHOR_FACE_SIZE = 20.dp

@Composable
private fun ErrorBanner(code: String) {
    StatusBanner(
        tone = StatusTone.BLOCKED,
        message = authorErrorText(code),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MessageItem(
    message: AuthorMessage,
    pending: AuthorMessage.Question?,
    onAction: (TaskAuthorAction) -> Unit,
) {
    when (message) {
        is AuthorMessage.User -> {
            if (message.text.isNotBlank()) UserBubble(message.text)
            SentImageRow(paths = message.images)
        }
        is AuthorMessage.Assistant -> MarkdownText(text = message.text)
        is AuthorMessage.Thinking -> ThinkingBlock(text = message.text, streaming = false)
        is AuthorMessage.Activity ->
            Text(
                text = activityText(message.text),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        "list_trigger_sources" -> stringResource(R.string.task_author_activity_list_trigger_sources)
        "sample_events" -> stringResource(R.string.task_author_activity_sample_events)
        "propose_task" -> stringResource(R.string.task_author_activity_propose_task)
        else -> name
    }

/** 用户消息：右对齐气泡，宽度不超过屏幕 80%（与对话页一致）。 */
@Composable
private fun UserBubble(text: String) {
    val spacing = LocalSpacing.current
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp * USER_BUBBLE_WIDTH_FRACTION
    Row(
        modifier = Modifier.fillMaxWidth(),
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
 * 提问卡片：问题 + 每个选项一行 + 固定的"自己输入"一行（与流程 AI 创建的提问卡一致，样式见 core-ui InlineQuestionCard）。
 * 回答后只保留问题文字，回答作为用户气泡出现在对话里。
 */
@Composable
private fun QuestionCard(
    question: AuthorMessage.Question,
    pending: Boolean,
    onAction: (TaskAuthorAction) -> Unit,
) {
    InlineQuestionCard(
        question = question.question,
        options = question.options,
        onAnswer = { onAction(TaskAuthorAction.Answer(it)) },
        customLabel = stringResource(R.string.task_author_custom_option),
        sendDescription = stringResource(R.string.task_author_send),
        modifier = Modifier.fillMaxWidth(),
        customPlaceholder = stringResource(R.string.task_author_custom_hint),
        interactive = pending,
        faceMood = BallFaceMood.WAITING,
    )
}

/** 流式生成中的这一轮：思考展开实时显示，正文逐字出现，工具调用显示动作名。 */
@Composable
private fun LiveTurn(live: AuthorLive) {
    Column {
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
            AwaitingReplyLine(
                text = stringResource(R.string.task_author_thinking),
                faceSize = AUTHOR_FACE_SIZE,
                textStyle = MaterialTheme.typography.labelSmall,
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
                .padding(vertical = LocalSpacing.current.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
        ) {
            AutopBallFace(
                mood = if (streaming) BallFaceMood.RUNNING else BallFaceMood.IDLE,
                modifier = Modifier.size(AUTHOR_FACE_SIZE),
            )
            Text(
                text = stringResource(R.string.task_author_thinking_label, text.length),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    issues: List<String>,
    warnings: List<String>,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AutopBallFace(mood = BallFaceMood.SUCCESS, modifier = Modifier.size(AUTHOR_FACE_SIZE))
            SectionTitle(
                text = stringResource(R.string.task_author_draft_title, draft.name),
                modifier = Modifier.weight(1f),
            )
        }
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
        Text(
            text =
                stringResource(
                    R.string.task_author_run_mode,
                    stringResource(runModeLabelRes(draft.runMode)),
                ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text =
                stringResource(
                    if (draft.backgroundScreen) R.string.task_display_virtual else R.string.task_display_main,
                ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (draft.hint.isNotBlank()) {
            Text(
                text = stringResource(R.string.task_author_hint, draft.hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        issues.forEach { issue ->
            Text(text = issue, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        warnings.forEach { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 运行模式的名称资源（与任务编辑页一致）。 */
private fun runModeLabelRes(mode: RunMode): Int =
    if (mode == RunMode.FULL) R.string.task_edit_run_mode_full else R.string.task_edit_run_mode_standard

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

/** 离开页面的守卫：AI 运行中按返回（含系统返回键）时询问是后台运行、取消运行，还是留在本页。 */
@Composable
private fun LeaveGuard(
    state: TaskAuthorUiState,
    onAction: (TaskAuthorAction) -> Unit,
) {
    BackHandler(enabled = state.author.busy) { onAction(TaskAuthorAction.Back) }
    if (state.leaveConfirm) {
        AlertDialog(
            onDismissRequest = { onAction(TaskAuthorAction.DismissLeave) },
            title = { Text(stringResource(R.string.task_author_leave_title)) },
            text = { Text(stringResource(R.string.task_author_leave_text)) },
            confirmButton = {
                TextButton(onClick = { onAction(TaskAuthorAction.LeaveKeepRunning) }) {
                    Text(stringResource(R.string.task_author_leave_keep))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(TaskAuthorAction.LeaveCancelRun) }) {
                    Text(stringResource(R.string.task_author_leave_cancel))
                }
            },
        )
    }
}
