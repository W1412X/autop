/**
 * 任务进度悬浮窗预览（docs/tech/08 §7，N182）：消息流 + 提问卡亮/暗各一例。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunState
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
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
            rows = PreviewRows,
            callbacks = PreviewCallbacks,
        )
    }

@Preview(name = "progress dark")
@Composable
internal fun ProgressScreenDarkPreview() =
    PreviewTheme(dark = true) {
        ProgressScreen(
            active = PreviewRun,
            rows = PreviewRows,
            callbacks = PreviewCallbacks,
        )
    }

@Preview(name = "progress ended")
@Composable
internal fun ProgressScreenEndedPreview() =
    PreviewTheme {
        ProgressScreen(
            active = null,
            rows = PreviewRows,
            callbacks = PreviewCallbacks,
        )
    }

private val PreviewCallbacks =
    ProgressCallbacks(
        onAnswer = {},
        onPauseResume = {},
        onStop = {},
        onOpenThread = {},
        onSteer = {},
        onContinueBlocked = {},
        onFinishBlocked = {},
        onClose = {},
    )

private val PreviewRows: List<ProgressRow> =
    listOf(
        ProgressRow.User("帮我看下当前屏幕", isSteer = false),
        ProgressRow.Thinking("当前屏幕上有设置与相机，需要确认用户意图。"),
        ProgressRow.Tool("tool.observe", ok = true, durationMs = 320, errorText = null),
        ProgressRow.Tool("tool.tap", ok = false, durationMs = 80, errorText = "点击手势被取消"),
        ProgressRow.Assistant(
            text = "已为你打开设置。",
            meta =
                ProgressRow.Finished(
                    status = RunOutcome.SUCCEEDED,
                    summary = null,
                    stepCount = 2,
                    durationMs = 42_000,
                    outputTokens = 512,
                ),
        ),
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
