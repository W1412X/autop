/**
 * 观测入口（agent-harness-overhaul WP1.6）：按选择方式分派 a11y/visual，
 * 自动选择时按 `device.observe.priority` 依次尝试直到信息足够，输出统一 JSON。
 * tool_optimize 后始终返回完整屏幕，不做差量、不产生变化结论。
 */
package com.example.autop.device.impl.observation

import com.example.autop.agent.ObservationRef
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.DeviceObserveSection
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.Observation
import com.example.autop.device.ObserveMode
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.ScreenJsonWire
import com.example.autop.device.core.SerializedScreen
import com.example.autop.device.impl.ObserveModeResolver
import com.example.autop.device.impl.ObserveSelection
import com.example.autop.device.impl.RecordedObservation
import com.example.autop.device.impl.SnapshotSerializer
import com.example.autop.device.impl.deviceHistory
import javax.inject.Inject
import javax.inject.Singleton

/** 一次观测的结果。 */
public class ObservationResult(
    public val observation: Observation,
    public val ref: ObservationRef,
    public val screen: SerializedScreen,
    public val snapshot: ScreenSnapshot,
)

/** 观测生成器（observe 工具与动作收尾共用）。 */
@Singleton
public class ScreenObserver
    @Inject
    constructor(
        private val preparer: ScreenPreparer,
        private val serializer: SnapshotSerializer,
        private val modes: ObserveModeResolver,
    ) {
        /**
         * 观测当前屏幕：[ObserveSelection.Auto] 按优先级自动选择，信息足够即停止；
         * [ObserveSelection.Explicit] 不自动更换。始终返回完整结果（tool_optimize）。
         */
        public suspend fun observe(
            ctx: ToolContext,
            selection: ObserveSelection,
        ): Outcome<ObservationResult> {
            val cfg = ctx.config[DeviceConfig.OBSERVE]
            val effective = modes.effective(cfg)
            return when (selection) {
                is ObserveSelection.Explicit ->
                    explicit(ctx, selection, effective, cfg)
                ObserveSelection.Auto -> auto(ctx, effective)
            }
        }

        private suspend fun explicit(
            ctx: ToolContext,
            selection: ObserveSelection.Explicit,
            effective: List<ObserveMode>,
            cfg: DeviceObserveSection,
        ): Outcome<ObservationResult> {
            val reason = modes.unavailableReason(selection.mode, cfg)
            if (reason != null) {
                return Outcome.Err(modeUnavailable(ctx, selection.mode, reason, effective))
            }
            return when (val got = preparer.prepare(ctx, selection.mode, effective)) {
                is Outcome.Err -> got
                is Outcome.Ok -> Outcome.Ok(record(ctx, selection, got.value, null))
            }
        }

        /** 自动选择：按优先级依次采集，信息不足或失败才换下一种，最后一个成功但信息不足的结果兜底。 */
        private suspend fun auto(
            ctx: ToolContext,
            effective: List<ObserveMode>,
        ): Outcome<ObservationResult> =
            when (val got = selectAuto(ctx, effective)) {
                is Outcome.Err -> got
                is Outcome.Ok -> {
                    val selected = got.value
                    Outcome.Ok(
                        record(
                            ctx,
                            ObserveSelection.Auto,
                            selected.prepared,
                            autoNote(ctx, selected),
                        ),
                    )
                }
            }

        private suspend fun selectAuto(
            ctx: ToolContext,
            effective: List<ObserveMode>,
        ): Outcome<Selected> {
            val candidates = modes.autoCandidates(ctx.config[DeviceConfig.OBSERVE])
            if (candidates.isEmpty()) {
                return Outcome.Err(
                    AppError(
                        code = DeviceErrorCodes.MODE_UNAVAILABLE,
                        message = "no observe mode is available for auto selection",
                        retryable = false,
                        hint = render(ctx, PROMPT_NO_AUTO_MODE, mapOf(VAR_OTHERS to othersText(effective))),
                    ),
                )
            }
            val skipped = mutableListOf<Skipped>()
            var fallback: Prepared? = null
            for (mode in candidates) {
                when (val got = preparer.prepare(ctx, mode, effective)) {
                    is Outcome.Err -> skipped += Skipped(mode, got.error.message)
                    is Outcome.Ok -> {
                        val reason = got.value.serialized.model.quality.insufficient
                        if (reason == null) {
                            return Outcome.Ok(Selected(got.value, skipped, allInsufficient = false))
                        }
                        skipped += Skipped(mode, insufficientText(ctx, reason))
                        fallback = got.value
                    }
                }
            }
            fallback?.let {
                return Outcome.Ok(Selected(it, skipped.dropLast(1), allInsufficient = true))
            }
            return Outcome.Err(
                AppError(
                    code = DeviceErrorCodes.NO_OBSERVATION,
                    message = skippedText(skipped),
                    retryable = true,
                    hint = render(ctx, PROMPT_ALL_FAILED),
                ),
            )
        }

        private fun record(
            ctx: ToolContext,
            selection: ObserveSelection,
            prepared: Prepared,
            note: String?,
        ): ObservationResult {
            val history = ctx.deviceHistory()
            history.last =
                RecordedObservation(
                    mode = prepared.mode,
                    selection = selection,
                    screen = prepared.serialized,
                    snapshot = prepared.snapshot,
                    frame = prepared.frame,
                )
            val fullObservation = prepared.serialized.observation
            val observation =
                if (note == null) {
                    fullObservation
                } else {
                    fullObservation.copy(text = note + "\n" + fullObservation.text)
                }
            return ObservationResult(
                observation = observation,
                ref =
                    ObservationRef(
                        fingerprint = observation.fingerprint,
                        packageName = observation.packageName,
                        activity = observation.activity,
                        summaryLine = observation.summaryLine,
                        nodeCount = prepared.serialized.model.quality.nodes,
                        hasImage = false,
                        isDiff = false,
                    ),
                screen = prepared.serialized,
                snapshot = prepared.snapshot,
            )
        }

        private fun autoNote(
            ctx: ToolContext,
            selected: Selected,
        ): String? {
            if (selected.skipped.isEmpty() && !selected.allInsufficient) return null
            val id =
                if (selected.allInsufficient) {
                    PROMPT_AUTO_ALL_INSUFFICIENT
                } else {
                    PROMPT_AUTO_SELECTED
                }
            return render(
                ctx,
                id,
                mapOf(
                    VAR_MODE to selected.prepared.mode.id,
                    VAR_SKIPPED to skippedText(selected.skipped),
                ),
            )
        }

        private fun skippedText(skipped: List<Skipped>): String =
            skipped.joinToString("；") { "${it.mode.id}：${it.reason}" }

        private fun othersText(effective: List<ObserveMode>): String =
            effective.joinToString("、") { it.id }.ifEmpty { "无" }

        private fun insufficientText(
            ctx: ToolContext,
            reason: String,
        ): String = render(ctx, ScreenJsonWire.PROMPT_INSUFFICIENT_REASON_PREFIX + reason)

        private fun render(
            ctx: ToolContext,
            id: String,
            vars: Map<String, String> = emptyMap(),
        ): String = serializer.configOf(ctx.config).renderPrompt(id, vars)

        private fun modeUnavailable(
            ctx: ToolContext,
            mode: ObserveMode,
            reason: String,
            effective: List<ObserveMode>,
        ): AppError {
            val hint =
                render(
                    ctx,
                    ScreenJsonWire.PROMPT_HINT_MODE_UNAVAILABLE,
                    mapOf(
                        VAR_MODE to mode.id,
                        VAR_REASON to reason,
                        VAR_OTHERS to othersText(effective.filter { it != mode }),
                    ),
                )
            return AppError(
                code = DeviceErrorCodes.MODE_UNAVAILABLE,
                message = "$reason（mode=${mode.id}）",
                hint = hint.takeIf { it.isNotBlank() },
                retryable = false,
            )
        }

        /** 自动选择结果：最终结果 + 被跳过的原因。 */
        private data class Selected(
            val prepared: Prepared,
            val skipped: List<Skipped>,
            /** 所有可用方式都信息不足，返回最后一个成功结果。 */
            val allInsufficient: Boolean,
        )

        private data class Skipped(
            val mode: ObserveMode,
            val reason: String,
        )

        private companion object {
            const val PROMPT_AUTO_SELECTED: String = "device.observe.auto_selected"
            const val PROMPT_AUTO_ALL_INSUFFICIENT: String = "device.observe.auto_all_insufficient"
            const val PROMPT_NO_AUTO_MODE: String = "device.observe.no_auto_mode"
            const val PROMPT_ALL_FAILED: String = "device.observe.all_failed"

            const val VAR_MODE: String = "mode"
            const val VAR_SKIPPED: String = "skipped"
            const val VAR_OTHERS: String = "others"
            const val VAR_REASON: String = "reason"
        }
    }
