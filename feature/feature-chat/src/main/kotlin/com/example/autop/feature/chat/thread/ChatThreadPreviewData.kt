/**
 * 对话页预览的时间线假数据（docs/tech/08 §4.2 的九种时间线样式全覆盖，
 * 供 [ChatThreadScreenPreviews] 使用）。
 */
package com.example.autop.feature.chat.thread

import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.agent.RunOutcome

/** 运行中时间线假数据：分叉标记、思考、计划卡、步骤、折叠组、steer 气泡、流式文本。 */
internal fun previewRunningEntries(): List<TimelineEntry> =
    listOf(
        TimelineEntry(
            item = TimelineItem.ForkMarker(parentThreadId = "t0", parentSeq = 3),
            seq = null,
            forkable = false,
        ),
        TimelineEntry(
            item = TimelineItem.UserBubble(text = "帮我订一张去上海的高铁票", isSteer = false),
            seq = 1,
            forkable = false,
        ),
        TimelineEntry(
            item = TimelineItem.ThinkingBlock(text = "需要先打开购票应用，查一下明天下午的车次。"),
            seq = 2,
            forkable = true,
        ),
        TimelineEntry(item = previewPlanCard(), seq = 3, forkable = true),
        TimelineEntry(
            item =
                previewStep(
                    toolId = "open_app",
                    argsJson = """{"name":"铁路12306"}""",
                    state = StepState.DONE,
                    resultText = "已打开应用",
                ).copy(stepNo = 1),
            seq = 4,
            forkable = true,
        ),
        TimelineEntry(
            item = TimelineItem.StepGroup(steps = previewReadGroup(firstStepNo = 2)),
            seq = 5,
            forkable = true,
        ),
        TimelineEntry(
            item = TimelineItem.UserBubble(text = "跳过登录，直接查余票", isSteer = true),
            seq = 6,
            forkable = true,
        ),
        TimelineEntry(
            item = TimelineItem.AssistantText(text = "好的，我直接查询余票。", streaming = true),
            seq = null,
            forkable = false,
        ),
        TimelineEntry(
            item =
                previewStep(
                    toolId = "tap",
                    argsJson = """{"text":"查询"}""",
                    state = StepState.RUNNING,
                    progressText = "正在点击…",
                ).copy(stepNo = 5),
            seq = null,
            forkable = false,
        ),
    )

/** 等待回答时间线假数据：结束元信息行（附着回复末尾）、失败步骤、暂停横幅与 AskCard（状态侧携带问题）。 */
internal fun previewAskEntries(): List<TimelineEntry> =
    listOf(
        TimelineEntry(
            item = TimelineItem.UserBubble(text = "帮我订一张去上海的高铁票", isSteer = false),
            seq = 1,
            forkable = false,
        ),
        TimelineEntry(
            item =
                previewStep(
                    toolId = "open_app",
                    argsJson = """{"name":"铁路12306"}""",
                    state = StepState.DONE,
                    resultText = "已打开应用",
                ).copy(stepNo = 1),
            seq = 2,
            forkable = true,
        ),
        TimelineEntry(
            item = TimelineItem.StepGroup(steps = previewReadGroup(firstStepNo = 2)),
            seq = 3,
            forkable = true,
        ),
        TimelineEntry(
            item =
                TimelineItem.AssistantText(
                    text = "已为你查到明天下午的车次。",
                    streaming = false,
                    meta = previewRunMeta(),
                ),
            seq = 4,
            forkable = true,
        ),
        TimelineEntry(
            item = TimelineItem.UserBubble(text = "订下午 2 点那班", isSteer = false),
            seq = 5,
            forkable = false,
        ),
        TimelineEntry(
            item =
                previewStep(
                    toolId = "tap",
                    argsJson = """{"x":500,"y":620}""",
                    state = StepState.FAILED,
                    resultText = "点击手势被取消",
                ).copy(stepNo = 5),
            seq = 6,
            forkable = true,
        ),
        TimelineEntry(
            item = TimelineItem.Banner(kind = BannerKind.PAUSED, detail = "用户触摸了屏幕"),
            seq = 7,
            forkable = false,
        ),
    )

/** 折叠组假数据：3 个读步骤，链路编号从 [firstStepNo] 起（N172）。 */
private fun previewReadGroup(firstStepNo: Int): List<TimelineItem.StepRow> =
    List(GROUP_COUNT) { index ->
        previewStep(
            toolId = "observe",
            argsJson = """{"mode":"a11y"}""",
            state = StepState.DONE,
        ).copy(stepNo = firstStepNo + index)
    }

private fun previewPlanCard(): TimelineItem.PlanCard =
    TimelineItem.PlanCard(
        items =
            listOf(
                PlanItem(step = "打开购票应用", status = PlanStatus.DONE),
                PlanItem(step = "搜索上海车次", status = PlanStatus.IN_PROGRESS),
                PlanItem(step = "选定车次并下单", status = PlanStatus.PENDING),
            ),
    )

private fun previewRunMeta(): TimelineItem.RunMeta =
    TimelineItem.RunMeta(
        outcome = RunOutcome.SUCCEEDED,
        summary = "已找到明天下午的 3 个车次",
        stepCount = 6,
        durationMs = 42_000,
        outputTokens = 512,
    )

/** 步骤行假数据：耗时 / 观测行按 [state] 推导（运行中无耗时，成功带观测行）。 */
private fun previewStep(
    toolId: String,
    argsJson: String,
    state: StepState,
    resultText: String? = null,
    progressText: String? = null,
): TimelineItem.StepRow =
    TimelineItem.StepRow(
        callId = toolId,
        toolId = toolId,
        argsJson = argsJson,
        state = state,
        durationMs = if (state == StepState.RUNNING) null else STEP_DURATION_MS,
        resultText = resultText,
        observationLine = if (state == StepState.DONE) OBSERVATION_LINE else null,
        imageRef = null,
        progressText = progressText,
    )

private const val STEP_DURATION_MS: Long = 820
private const val OBSERVATION_LINE: String = "首页 · 42 个节点"
private const val GROUP_COUNT: Int = 3
