/**
 * AI 创建流程页（与对话页一致的交互）：用户消息为气泡，助手为 Markdown，提问为选项卡片
 * （选项 + 固定的“自己输入”一行），草稿为可折叠的步骤卡片，底部输入栏始终可用。
 */
@file:Suppress("LongMethod") // Compose 页面：布局声明

package com.example.autop.feature.flows.author

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.example.autop.core.thread.ui.AttachImageButton
import com.example.autop.core.thread.ui.PendingImageStrip
import com.example.autop.core.thread.ui.SentImageRow
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopChip
import com.example.autop.core.ui.component.AutopComposer
import com.example.autop.core.ui.component.AutopModelSelector
import com.example.autop.core.ui.component.AutopOutlinedButton
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
import com.example.autop.feature.flows.R
import com.example.autop.feature.flows.childSteps
import com.example.autop.feature.flows.nodeDetail
import com.example.autop.feature.flows.nodeType
import com.example.autop.feature.flows.nodeTypeLabel
import com.example.autop.feature.flows.stringByName
import com.example.autop.flow.AuthorLive
import com.example.autop.flow.AuthorMessage
import com.example.autop.flow.AuthorState
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowIssue
import com.example.autop.flow.IssueSeverity
import com.example.autop.flow.Node

/** 用户气泡最大宽度占屏幕的比例（与对话页一致）。 */
private const val USER_BUBBLE_WIDTH_FRACTION: Float = 0.8f

/** 首页给出的示例提示语（点击填入输入框）。 */
private val EXAMPLE_KEYS: List<Int> =
    listOf(R.string.author_example_brightness, R.string.author_example_message, R.string.author_example_check_in)

/** AI 创建页。 */
@Suppress("CyclomaticComplexMethod") // 页面布局：各状态（空态、提问、草稿、示范确认、错误）各自一个分支
@Composable
public fun FlowAuthorScreen(
    state: FlowAuthorUiState,
    onAction: (FlowAuthorAction) -> Unit,
) {
    val startVoice =
        rememberVoiceStarter(
            onGranted = { onAction(FlowAuthorAction.StartVoice) },
            onDenied = { onAction(FlowAuthorAction.VoiceFailed("speech.permission")) },
        )
    val spacing = LocalSpacing.current
    val author = state.author
    val listState = rememberLazyListState()
    val stick = rememberStickToBottom(listState)
    // 用户发出消息后无条件回到最新（即便此前上滑）；其余增长只在用户停在底部时跟随。
    LaunchedEffect(author.messages.size) {
        if (author.messages.lastOrNull() is AuthorMessage.User) stick.follow()
    }
    // 打开页面时就有的草稿（修改既有流程/运行中回到未结束会话）：直接贴底。
    // 恢复的状态可能晚于首帧到达（修改既有流程要读库），此时以"草稿不是运行中产出的"为准。
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
        val intro = if (author.messages.isEmpty() && author.live == null) 1 else 0
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
            title = stringResource(R.string.author_title),
            onBack = { onAction(FlowAuthorAction.Back) },
            actions = {
                AutopTonalButton(
                    text = stringResource(R.string.flow_save),
                    onClick = { onAction(FlowAuthorAction.Save) },
                    enabled = author.draft != null && !author.busy,
                )
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(horizontal = spacing.screenEdge, vertical = spacing.md),
        ) {
            if (author.messages.isEmpty() && author.live == null) item { Intro(onAction) }
            items(author.messages) { message ->
                MessageItem(message = message, pending = author.pendingQuestion, onAction = onAction)
            }
            // 草稿是此前轮次的产物，排在正在流式输出的这一轮之前，跟随底部时看到的始终是最新内容。
            author.draft?.let { draft ->
                item { DraftCard(draft = draft, issues = author.issues, onAction = onAction) }
            }
            author.live?.let { live -> item { LiveTurn(live = live) } }
            if (author.busy && author.live == null && author.waitingOnAi()) {
                item {
                    AwaitingReplyLine(
                        text = stringResource(R.string.author_thinking),
                        faceSize = AUTHOR_FACE_SIZE,
                        textStyle = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            author.errorCode?.let { item { ErrorBanner(it) } }
            state.saveError?.let { item { ErrorBanner(it) } }
            if (state.imageError) item { ErrorBanner(AUTHOR_IMAGE_FAILED) }
        }
        author.pendingDemonstration?.let { goal -> DemoConfirm(goal = goal, onAction = onAction) }
        VoiceErrorToast(code = state.voiceError)
        LeaveGuard(state = state, onAction = onAction)
        if (state.images.isNotEmpty()) {
            PendingImageStrip(
                images = state.images,
                onRemove = { onAction(FlowAuthorAction.RemoveImage(it)) },
                modifier = Modifier.padding(horizontal = spacing.screenEdge),
            )
        }
        AutopComposer(
            value = state.input,
            onValueChange = { onAction(FlowAuthorAction.SetInput(it)) },
            leading = {
                AttachImageButton(
                    onImages = { onAction(FlowAuthorAction.AddImages(it)) },
                    onCaptureFailed = { onAction(FlowAuthorAction.ImageFailed) },
                )
            },
            spec =
                InputBarSpec(
                    placeholder =
                        stringResource(
                            if (author.pendingQuestion != null) {
                                R.string.author_input_answer_hint
                            } else {
                                R.string.author_input_hint
                            },
                        ),
                    maxLines = 4,
                ),
            modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge),
            top = {
                AutopModelSelector(
                    selectedId = state.modelProfileId,
                    profiles = state.modelOptions,
                    enabled = !author.busy,
                    onSelect = { onAction(FlowAuthorAction.SetModel(it)) },
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
            onVoice = { if (state.listening) onAction(FlowAuthorAction.StopVoice) else startVoice() },
            onSend = { onAction(FlowAuthorAction.Send) },
            onStop = { onAction(FlowAuthorAction.Stop) },
        )
    }
}

/** 没有在等用户的提问或示范确认，界面上只剩 AI 在工作。 */
private fun AuthorState.waitingOnAi(): Boolean = pendingQuestion == null && pendingDemonstration == null

@Composable
private fun Intro(onAction: (FlowAuthorAction) -> Unit) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.author_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.author_examples_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EXAMPLE_KEYS.forEach { key ->
            val example = stringResource(key)
            AutopChip(
                text = example,
                selected = false,
                onClick = { onAction(FlowAuthorAction.SetInput(example)) },
            )
        }
    }
}

@Composable
private fun ErrorBanner(code: String) {
    StatusBanner(
        tone = StatusTone.BLOCKED,
        message =
            stringResource(
                if (code == AUTHOR_IMAGE_FAILED) R.string.flow_author_image_failed else llmErrorTextRes(code),
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MessageItem(
    message: AuthorMessage,
    pending: AuthorMessage.Question?,
    onAction: (FlowAuthorAction) -> Unit,
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
                text = stringByName("author_activity_${message.text}", message.text),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        is AuthorMessage.Question -> QuestionCard(message, pending = message === pending, onAction = onAction)
    }
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
 * 提问卡片：问题 + 每个选项一行 + 固定的“自己输入”一行（与对话页的提问卡一致）。
 * 回答后只保留问题文字，回答作为用户气泡出现在对话里。
 */
@Composable
private fun QuestionCard(
    question: AuthorMessage.Question,
    pending: Boolean,
    onAction: (FlowAuthorAction) -> Unit,
) {
    InlineQuestionCard(
        question = question.question,
        options = question.options,
        onAnswer = { onAction(FlowAuthorAction.Answer(it)) },
        customLabel = stringResource(R.string.author_custom_option),
        sendDescription = stringResource(R.string.author_send),
        modifier = Modifier.fillMaxWidth(),
        customPlaceholder = stringResource(R.string.author_custom_hint),
        interactive = pending,
        faceMood = BallFaceMood.WAITING,
    )
}

/** 流式生成中的这一轮：思考展开实时显示，正文逐字出现，工具调用显示动作名。 */
@Composable
private fun LiveTurn(live: AuthorLive) {
    Column {
        if (live.thinking.isNotBlank()) {
            ThinkingBlock(
                text = live.thinking,
                streaming = live.text.isEmpty() && live.tool == null,
            )
        }
        if (live.text.isNotBlank()) MarkdownText(text = live.text)
        live.tool?.let {
            Text(
                text = stringByName("author_activity_$it", it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (live.thinking.isBlank() && live.text.isBlank() && live.tool == null) {
            AwaitingReplyLine(
                text = stringResource(R.string.author_thinking),
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
                text = stringResource(R.string.author_thinking_label, text.length),
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

@Composable
private fun DemoConfirm(
    goal: String,
    onAction: (FlowAuthorAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    StatusBanner(
        tone = StatusTone.WAITING,
        title = stringResource(R.string.author_demo_title),
        message = stringResource(R.string.author_demo_text, goal),
        modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge),
        leading = {
            AutopBallFace(mood = BallFaceMood.WAITING, modifier = Modifier.size(AUTHOR_FACE_SIZE))
        },
        actions = {
            AutopButton(text = stringResource(R.string.author_demo_allow), onClick = {
                onAction(FlowAuthorAction.Demonstration(true))
            })
            AutopOutlinedButton(text = stringResource(R.string.author_demo_deny), onClick = {
                onAction(FlowAuthorAction.Demonstration(false))
            })
        },
    )
}

/** 草稿卡片：名称、说明、参数、步骤（嵌套步骤缩进）、校验问题与高级编辑入口。 */
@Composable
private fun DraftCard(
    draft: FlowDefinition,
    issues: List<FlowIssue>,
    onAction: (FlowAuthorAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AutopBallFace(mood = BallFaceMood.SUCCESS, modifier = Modifier.size(AUTHOR_FACE_SIZE))
            SectionTitle(
                text = stringResource(R.string.author_draft_title, draft.name),
                modifier = Modifier.weight(1f),
            )
        }
        if (draft.description.isNotBlank()) {
            Text(
                text = draft.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (draft.params.isNotEmpty()) {
            Text(
                text = stringResource(R.string.author_draft_params, draft.params.joinToString("、") { it.name }),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }
        StepList(steps = draft.steps, depth = 0)
        issues.forEach { IssueLine(it) }
        AutopOutlinedButton(
            text = stringResource(R.string.author_advanced),
            onClick = { onAction(FlowAuthorAction.OpenAdvanced) },
            modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
        )
    }
}

@Composable
private fun StepList(
    steps: List<Node>,
    depth: Int,
) {
    steps.forEachIndexed { i, node ->
        Row(
            modifier = Modifier.padding(start = LocalSpacing.current.lg * depth, top = LocalSpacing.current.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${i + 1}. ${node.name ?: nodeTypeLabel(nodeType(node))}  ${nodeDetail(node)}".trim(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        childSteps(node).forEach { (_, children) -> StepList(steps = children, depth = depth + 1) }
    }
}

@Composable
private fun IssueLine(issue: FlowIssue) {
    val error = issue.severity == IssueSeverity.ERROR
    Text(
        text = (if (error) "✗ " else "! ") + (issue.nodeId?.let { "$it：" } ?: "") + issue.message,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = LocalSpacing.current.xs),
    )
}

/** 图片读取或拍照失败的错误码（界面内部使用）。 */
private const val AUTHOR_IMAGE_FAILED: String = "flow.author_image_failed"

/** 创建页表情尺寸（P1-14：生成/提问/草稿三处 20dp）。 */
private val AUTHOR_FACE_SIZE = 20.dp

/** 离开页面的守卫：AI 运行中按返回（含系统返回键）时询问是后台运行、取消运行，还是留在本页。 */
@Composable
private fun LeaveGuard(
    state: FlowAuthorUiState,
    onAction: (FlowAuthorAction) -> Unit,
) {
    BackHandler(enabled = state.author.busy) { onAction(FlowAuthorAction.Back) }
    if (state.leaveConfirm) {
        AlertDialog(
            onDismissRequest = { onAction(FlowAuthorAction.DismissLeave) },
            title = { Text(stringResource(R.string.flow_author_leave_title)) },
            text = { Text(stringResource(R.string.flow_author_leave_text)) },
            confirmButton = {
                TextButton(onClick = { onAction(FlowAuthorAction.LeaveKeepRunning) }) {
                    Text(stringResource(R.string.flow_author_leave_keep))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(FlowAuthorAction.LeaveCancelRun) }) {
                    Text(stringResource(R.string.flow_author_leave_cancel))
                }
            },
        )
    }
}
