/**
 * 任务进度悬浮窗预览（docs/tech/08 §7，N182）：消息流 + 提问卡亮/暗各一例，以及结束态。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunState
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.thread.ui.StepState
import com.example.autop.core.thread.ui.TimelineEntry
import com.example.autop.core.thread.ui.TimelineItem
import com.example.autop.core.ui.preview.PreviewTheme
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus

@Preview(name = "progress light")
@Composable
internal fun ProgressScreenPreview() =
    PreviewTheme {
        ProgressScreen(
            active = PreviewRun,
            entries = PreviewEntries,
            callbacks = PreviewCallbacks,
        )
    }

@Preview(name = "progress dark")
@Composable
internal fun ProgressScreenDarkPreview() =
    PreviewTheme(dark = true) {
        ProgressScreen(
            active = PreviewRun,
            entries = PreviewEntries,
            callbacks = PreviewCallbacks,
        )
    }

@Preview(name = "progress ended")
@Composable
internal fun ProgressScreenEndedPreview() =
    PreviewTheme {
        ProgressScreen(
            active = null,
            entries = PreviewEntries,
            callbacks = PreviewCallbacks,
        )
    }

private val PreviewCallbacks =
    ProgressCallbacks(
        onAnswer = {},
        onPauseResume = {},
        onStop = {},
        onStopFlow = {},
        onOpenThread = {},
        onSteer = {},
        onContinueBlocked = {},
        onFinishBlocked = {},
        onClose = {},
    )

private val PreviewEntries: List<TimelineEntry> =
    listOf(
        entryOf(TimelineItem.UserBubble(text = "帮我看下当前屏幕", isSteer = false)),
        entryOf(TimelineItem.ThinkingBlock(text = "当前屏幕上有设置与相机，需要确认用户意图。")),
        entryOf(stepOf(callId = "c1", toolId = "observe", state = StepState.DONE, durationMs = 320)),
        entryOf(
            stepOf(
                callId = "c2",
                toolId = "scroll",
                state = StepState.RUNNING,
                durationMs = null,
                progressText = "正在滚动 3/10",
            ),
        ),
        entryOf(
            stepOf(
                callId = "c3",
                toolId = "tap",
                state = StepState.FAILED,
                durationMs = 80,
                resultText = "点击手势被取消",
            ),
        ),
        entryOf(
            TimelineItem.AssistantText(
                text = "已为你打开设置。",
                streaming = false,
                meta =
                    TimelineItem.RunMeta(
                        outcome = RunOutcome.SUCCEEDED,
                        summary = null,
                        stepCount = 2,
                        durationMs = 42_000,
                        outputTokens = 512,
                    ),
            ),
        ),
    )

private fun entryOf(item: TimelineItem): TimelineEntry = TimelineEntry(item = item, seq = null, forkable = false)

private fun stepOf(
    callId: String,
    toolId: String,
    state: StepState,
    durationMs: Long?,
    resultText: String? = null,
    progressText: String? = null,
): TimelineItem.StepRow =
    TimelineItem.StepRow(
        callId = callId,
        toolId = toolId,
        argsJson = "{}",
        state = state,
        durationMs = durationMs,
        resultText = resultText,
        observationLine = null,
        imageRef = null,
        progressText = progressText,
    )

private val PreviewRun: ActiveRunInfo? =
    ActiveRunInfo(
        run =
            RunRecord(
                id = RunId("run-preview"),
                taskId = null,
                threadId = ThreadId("thread-preview"),
                origin = RunOrigin.CHAT,
                status = RunStatus.WAITING_USER,
                triggerContext = emptyMap(),
                createdAt = 0L,
                startedAt = null,
                endedAt = null,
                summary = null,
                errorCode = null,
                pauseReason = null,
                waitingQuestion = "要打开哪个应用？",
                waitingOptions = listOf("设置", "相机"),
            ),
        state = RunState.SAMPLING,
        currentStep = "正在等待你的回答…",
        stepCount = 3,
        plan = emptyList(),
    )
