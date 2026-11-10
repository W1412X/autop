/**
 * 观测入口（agent-harness-overhaul WP1.6）：按选择方式分派 a11y/visual/llm，
 * 自动选择时按 `device.observe.priority` 依次尝试直到信息足够，输出统一 JSON 与屏幕变化结论。
 */
package com.example.autop.device.impl.observation

import android.graphics.BitmapFactory
import com.example.autop.agent.ObservationRef
import com.example.autop.agent.ScreenDelta
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.DeviceObserveSection
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.Observation
import com.example.autop.device.ObservationMode
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.Screenshot
import com.example.autop.device.VisualFrame
import com.example.autop.device.VisualScreenParser
import com.example.autop.device.VisualTree
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.example.autop.device.core.DiffOutcome
import com.example.autop.device.core.ScreenFingerprint
import com.example.autop.device.core.ScreenJsonDiffer
import com.example.autop.device.core.ScreenJsonRenderer
import com.example.autop.device.core.ScreenJsonWire
import com.example.autop.device.core.ScreenNodeMatcher
import com.example.autop.device.core.SerializedScreen
import com.example.autop.device.core.SerializerConfig
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.ObserveModeResolver
import com.example.autop.device.impl.ObserveSelection
import com.example.autop.device.impl.RecordedObservation
import com.example.autop.device.impl.SnapshotCapturer
import com.example.autop.device.impl.SnapshotSerializer
import com.example.autop.device.impl.deviceHistory
import com.example.autop.device.impl.vision.FrameSignature
import com.example.autop.device.impl.vision.frameSignatureOf
import com.example.autop.device.impl.vision.llm.LlmScreenParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** 采集＋序列化的中间结果（未写历史）。 */
internal class Prepared(
    val mode: ObserveMode,
    val snapshot: ScreenSnapshot,
    val serialized: SerializedScreen,
    val frame: FrameSignature?,
)

/** 一次观测的结果。 */
public class ObservationResult(
    public val observation: Observation,
    public val ref: ObservationRef,
    public val screen: SerializedScreen,
    public val snapshot: ScreenSnapshot,
    /** 相对最近观测的屏幕变化结论（WP2.3）。 */
    public val delta: ScreenDelta,
)

/** 观测生成器（observe 工具与动作收尾共用）。 */
@Singleton
public class ScreenObserver
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val capturer: SnapshotCapturer,
        private val contentAreas: ContentAreaProvider,
        private val visualParser: VisualScreenParser,
        private val llmParser: LlmScreenParser,
        private val serializer: SnapshotSerializer,
        private val modes: ObserveModeResolver,
    ) {
        /**
         * 观测当前屏幕：[ObserveSelection.Auto] 按优先级自动选择，信息足够即停止；
         * [ObserveSelection.Explicit] 不自动更换。[full] 为 false 时同方式、同页面出差量（llm 恒完整）；
         * [region] 只在使用 llm 时生效（内容区 0–1000）。
         */
        public suspend fun observe(
            ctx: ToolContext,
            selection: ObserveSelection,
            full: Boolean = false,
            region: Rect? = null,
        ): Outcome<ObservationResult> {
            val cfg = ctx.config[DeviceConfig.OBSERVE]
            val effective = modes.effective(cfg)
            return when (selection) {
                is ObserveSelection.Explicit ->
                    explicit(ctx, selection, effective, full, region, cfg)
                ObserveSelection.Auto -> auto(ctx, effective, full, region)
            }
        }

        private suspend fun explicit(
            ctx: ToolContext,
            selection: ObserveSelection.Explicit,
            effective: List<ObserveMode>,
            full: Boolean,
            region: Rect?,
            cfg: DeviceObserveSection,
        ): Outcome<ObservationResult> {
            val reason = modes.unavailableReason(selection.mode, cfg)
            if (reason != null) {
                return Outcome.Err(modeUnavailable(ctx, selection.mode, reason, effective))
            }
            return when (val got = prepare(ctx, selection.mode, region, effective)) {
                is Outcome.Err -> got
                is Outcome.Ok -> Outcome.Ok(record(ctx, selection, got.value, full, null))
            }
        }

        /** 自动选择：按优先级依次采集，信息不足或失败才换下一种，最后一个成功但信息不足的结果兜底。 */
        private suspend fun auto(
            ctx: ToolContext,
            effective: List<ObserveMode>,
            full: Boolean,
            region: Rect?,
        ): Outcome<ObservationResult> =
            when (val got = selectAuto(ctx, effective, region)) {
                is Outcome.Err -> got
                is Outcome.Ok -> {
                    val selected = got.value
                    Outcome.Ok(
                        record(
                            ctx,
                            ObserveSelection.Auto,
                            selected.prepared,
                            full,
                            autoNote(ctx, selected),
                        ),
                    )
                }
            }

        private suspend fun selectAuto(
            ctx: ToolContext,
            effective: List<ObserveMode>,
            region: Rect?,
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
                when (val got = prepare(ctx, mode, if (mode == ObserveMode.LLM) region else null, effective)) {
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

        private suspend fun prepare(
            ctx: ToolContext,
            mode: ObserveMode,
            region: Rect?,
            effective: List<ObserveMode>,
        ): Outcome<Prepared> =
            when (mode) {
                ObserveMode.A11Y -> a11y(ctx, effective)
                ObserveMode.VISUAL -> visual(ctx, effective)
                ObserveMode.LLM -> llm(ctx, region, effective)
            }

        /** 无障碍观测：应用窗口缺失/空树照常返回（是否换方式由自动选择决定）。 */
        private suspend fun a11y(
            ctx: ToolContext,
            effective: List<ObserveMode>,
        ): Outcome<Prepared> =
            when (val got = screenReader.snapshot()) {
                is Outcome.Err -> got
                is Outcome.Ok -> {
                    val snap = got.value
                    val noAppWindow = snap.windows.none { it.type == WindowType.APPLICATION }
                    Outcome.Ok(
                        Prepared(
                            mode = ObserveMode.A11Y,
                            snapshot = snap,
                            serialized =
                                serializer.serialize(
                                    snap,
                                    ObserveMode.A11Y,
                                    ctx.config,
                                    effective,
                                    noAppWindow = noAppWindow,
                                ),
                            frame = null,
                        ),
                    )
                }
            }

        /** 视觉观测：截图 → 视觉流水线 → 伪树；截图错误原样返回，解析失败用独立错误码。 */
        @Suppress(
            "ReturnCount", // 截图错误、超时、非取消异常、不可解析各自失败出口，是失败分流语义
            "TooGenericExceptionCaught", // 解析器实现可能抛任意运行时异常，统一转 VISUAL_PARSE_FAILED（WP7.6）
        )
        private suspend fun visual(
            ctx: ToolContext,
            effective: List<ObserveMode>,
        ): Outcome<Prepared> {
            val base = capturer.buildSnapshot()
            val shot =
                when (val got = screenReader.screenshot()) {
                    is Outcome.Err -> return got
                    is Outcome.Ok -> got.value
                }
            val timeoutMs = ctx.config[DeviceConfig.VISION].timeoutMs
            val tree =
                try {
                    withTimeout(timeoutMs) { visualParser.synthesize(frameOf(shot, base)) }
                } catch (e: TimeoutCancellationException) {
                    return Outcome.Err(visualParseFailed("visual parse timeout (${timeoutMs}ms)", e))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Outcome.Err(visualParseFailed(e.message ?: e.javaClass.simpleName, e))
                } ?: return Outcome.Err(visualParseFailed("visual parse failed: unreadable frame", null))
            val snap = adoptVisualTree(base, tree)
            return Outcome.Ok(
                Prepared(
                    mode = ObserveMode.VISUAL,
                    snapshot = snap,
                    serialized = serializer.serialize(snap, ObserveMode.VISUAL, ctx.config, effective),
                    frame = signatureOf(shot),
                ),
            )
        }

        /** llm 观测：独立 VISION 请求，只把 JSON 作为工具结果（§4）；签名与模型来自同一张截图。 */
        private suspend fun llm(
            ctx: ToolContext,
            region: Rect?,
            effective: List<ObserveMode>,
        ): Outcome<Prepared> {
            val base = capturer.buildSnapshot()
            val shot =
                when (val got = screenReader.screenshot()) {
                    is Outcome.Err -> return got
                    is Outcome.Ok -> got.value
                }
            val frame = frameOf(shot, base)
            val app = base.foregroundPackage.orEmpty()
            val page = base.activity?.substringAfterLast('.')
            return when (
                val got =
                    llmParser.parse(
                        ctx = ctx,
                        frame = frame,
                        region = region,
                        app = app,
                        page = page,
                        effectiveModes = effective,
                    )
            ) {
                is Outcome.Err -> got
                is Outcome.Ok ->
                    Outcome.Ok(
                        Prepared(
                            mode = ObserveMode.LLM,
                            snapshot = base,
                            serialized = serializedOf(ctx, got.value.model, base),
                            frame = got.value.signature,
                        ),
                    )
            }
        }

        private fun record(
            ctx: ToolContext,
            selection: ObserveSelection,
            prepared: Prepared,
            full: Boolean,
            note: String?,
        ): ObservationResult {
            val history = ctx.deviceHistory()
            val prev = history.last
            val config = serializer.configOf(ctx.config)
            val outcome =
                if (full || prev == null || prev.mode != prepared.mode) {
                    DiffOutcome(prepared.serialized.observation, matchOf(prev, prepared, config))
                } else {
                    ScreenJsonDiffer.diff(prev.screen, prepared.serialized, config)
                }
            val delta = ScreenDeltas.of(prev, prepared, outcome.match, ctx.config[DeviceConfig.CHANGE])
            history.last =
                RecordedObservation(
                    mode = prepared.mode,
                    selection = selection,
                    screen = prepared.serialized,
                    snapshot = prepared.snapshot,
                    frame = prepared.frame,
                )
            val observation =
                if (note == null) {
                    outcome.observation
                } else {
                    outcome.observation.copy(text = note + "\n" + outcome.observation.text)
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
                        isDiff = observation.mode == ObservationMode.DIFF,
                    ),
                screen = prepared.serialized,
                snapshot = prepared.snapshot,
                delta = delta,
            )
        }

        /** 完整观测时也要配对（变化结论与是否输出差量无关）；不可比较返回 null。 */
        private fun matchOf(
            prev: RecordedObservation?,
            prepared: Prepared,
            config: SerializerConfig,
        ): ScreenNodeMatcher.Result? {
            val base = prev ?: return null
            if (base.mode != prepared.mode || prepared.mode == ObserveMode.LLM) return null
            if (base.screen.observation.packageName != prepared.serialized.observation.packageName ||
                base.screen.observation.activity != prepared.serialized.observation.activity
            ) {
                return null
            }
            return ScreenJsonDiffer.matchOf(base.screen, prepared.serialized, config)
        }

        /** llm 模型 → 观测文本（a11y/visual 由 [SnapshotSerializer] 直接产出）。 */
        private fun serializedOf(
            ctx: ToolContext,
            model: ScreenModel,
            base: ScreenSnapshot,
        ): SerializedScreen {
            val config = serializer.configOf(ctx.config)
            val suggestion =
                model.quality.insufficient?.let { code ->
                    config.renderPrompt(
                        ScreenJsonWire.PROMPT_INSUFFICIENT,
                        mapOf(
                            VAR_REASON to
                                config.renderPrompt(
                                    ScreenJsonWire.PROMPT_INSUFFICIENT_REASON_PREFIX + code,
                                    emptyMap(),
                                ),
                            VAR_SUGGEST to (model.quality.suggest?.id ?: ""),
                        ),
                    )
                }
            val text =
                ScreenJsonRenderer.render(
                    model = model,
                    coordText = config.renderPrompt(ScreenJsonWire.PROMPT_COORD, emptyMap()),
                    suggestion = suggestion,
                )
            val window = model.windows.firstOrNull()
            val observation =
                Observation(
                    text = text,
                    mode = ObservationMode.FULL,
                    observeMode = ObserveMode.LLM,
                    fingerprint = ScreenFingerprint.of(base.foregroundPackage, base.activity, model.appFlatten()),
                    packageName = base.foregroundPackage,
                    activity = base.activity,
                    summaryLine =
                        "${window?.app.orEmpty()} / ${window?.page.orEmpty()}，${model.quality.nodes} 个节点",
                    interactiveCount = model.quality.interactive,
                    nodeCount = model.quality.nodes,
                    semanticPoor = false,
                )
            return SerializedScreen(observation, model)
        }

        /** 视觉伪树替换：保留前台/尺寸信息，窗口收敛为单个 APPLICATION 窗口（§3.2）。 */
        private fun adoptVisualTree(
            base: ScreenSnapshot,
            tree: VisualTree,
        ): ScreenSnapshot {
            val window =
                WindowSnapshot(
                    windowId = 0,
                    type = WindowType.APPLICATION,
                    layer = 0,
                    packageName = base.foregroundPackage,
                    title = base.foregroundPackage,
                    bounds = Rect(0, 0, base.screenWidth, base.screenHeight),
                    active = true,
                    focused = true,
                    rootIdx = tree.rootIdx,
                )
            return base.copy(windows = listOf(window), nodes = tree.nodes, truncated = false)
        }

        private fun frameOf(
            shot: Screenshot,
            base: ScreenSnapshot,
        ): VisualFrame =
            VisualFrame(
                imageBytes = shot.jpegBytes ?: ByteArray(0),
                width = shot.width,
                height = shot.height,
                rotation = base.rotation,
                capturedAt = base.capturedAt,
                contentArea = contentAreas.base(),
            )

        private fun signatureOf(shot: Screenshot): FrameSignature? {
            val bytes = shot.jpegBytes ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return try {
                frameSignatureOf(bitmap)
            } finally {
                bitmap.recycle()
            }
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

        private fun visualParseFailed(
            message: String,
            cause: Throwable?,
        ): AppError =
            AppError(
                code = DeviceErrorCodes.VISUAL_PARSE_FAILED,
                message = message,
                retryable = true,
                cause = cause,
            )

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
            const val VAR_SUGGEST: String = "suggest"
        }
    }
