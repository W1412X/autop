/**
 * 屏幕观测（docs/design/observation-modes-coordinate-actions §2、§3）：
 * 按显式模式分派 a11y / visual / llm，输出统一 JSON；差量基线按模式保存在本 Run。
 */
package com.example.autop.device.impl.observation

import com.example.autop.agent.ObservationRef
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.DeviceConfig
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
import com.example.autop.device.core.ScreenFingerprint
import com.example.autop.device.core.ScreenJsonDiffer
import com.example.autop.device.core.ScreenJsonRenderer
import com.example.autop.device.core.ScreenJsonWire
import com.example.autop.device.core.SerializedScreen
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.ObserveModeResolver
import com.example.autop.device.impl.SnapshotCapturer
import com.example.autop.device.impl.SnapshotSerializer
import com.example.autop.device.impl.deviceHistory
import com.example.autop.device.impl.vision.llm.LlmScreenParser
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** 一次观测的结果：观测文本 + 摘要引用 + 本次序列化（下次差量的基线）。 */
public data class ObservationResult(
    val observation: Observation,
    val ref: ObservationRef,
    val screen: SerializedScreen,
)

/** 观测生成器（observe 工具与 [ActionEpilogue] 共用）。 */
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
         * 观测当前屏幕。[mode] 必须按当前配置可用，否则 `device.mode_unavailable`；
         * [full] 为 false 时 a11y/visual 出与上次同模式的差量（llm 恒完整）；
         * [region] 仅 llm 生效（内容区 0–1000）。
         */
        public suspend fun observe(
            ctx: ToolContext,
            mode: ObserveMode,
            full: Boolean = false,
            region: Rect? = null,
        ): Outcome<ObservationResult> {
            val observeConfig = ctx.config[DeviceConfig.OBSERVE]
            val reason = modes.unavailableReason(mode, observeConfig)
            if (reason != null) {
                return Outcome.Err(modeUnavailable(ctx, mode, reason, modes.effective(observeConfig)))
            }
            val effective = modes.effective(observeConfig)
            val current =
                when (mode) {
                    ObserveMode.A11Y -> a11y(ctx, effective)
                    ObserveMode.VISUAL -> visual(ctx, effective)
                    ObserveMode.LLM -> llm(ctx, region, effective)
                }
            return when (current) {
                is Outcome.Err -> current
                is Outcome.Ok -> Outcome.Ok(record(ctx, mode, full, current.value))
            }
        }

        private suspend fun a11y(
            ctx: ToolContext,
            effective: List<ObserveMode>,
        ): Outcome<Prepared> =
            when (val got = screenReader.snapshot()) {
                is Outcome.Err -> got
                is Outcome.Ok ->
                    Outcome.Ok(
                        Prepared(
                            snapshot = got.value,
                            serialized = serializer.serialize(got.value, ObserveMode.A11Y, ctx.config, effective),
                        ),
                    )
            }

        /**
         * 显式视觉观测：截图 → 视觉流水线 → 伪树（§2.2）。
         * 解析失败不换模式，返回截图失败错误。
         */
        private suspend fun visual(
            ctx: ToolContext,
            effective: List<ObserveMode>,
        ): Outcome<Prepared> {
            val base = capturer.buildSnapshot()
            val shot =
                (screenReader.screenshot() as? Outcome.Ok)?.value
                    ?: return Outcome.Err(parseFailed("视觉解析截图失败"))
            val frame = frameOf(shot, base)
            val timeoutMs = ctx.config[DeviceConfig.VISION].timeoutMs
            val tree =
                try {
                    withTimeout(timeoutMs) { visualParser.synthesize(frame) }
                } catch (e: TimeoutCancellationException) {
                    return Outcome.Err(parseFailed("视觉解析超时（${timeoutMs}ms）：${e.message}"))
                } ?: return Outcome.Err(parseFailed("视觉解析失败（截图不可解析或 secure 窗口）"))
            val snap = adoptVisualTree(base, tree)
            return Outcome.Ok(Prepared(snap, serializer.serialize(snap, ObserveMode.VISUAL, ctx.config, effective)))
        }

        /** llm 观测：独立 VISION 请求，只把 JSON 作为工具结果（§4）。 */
        private suspend fun llm(
            ctx: ToolContext,
            region: Rect?,
            effective: List<ObserveMode>,
        ): Outcome<Prepared> {
            val base = capturer.buildSnapshot()
            val shot =
                (screenReader.screenshot() as? Outcome.Ok)?.value
                    ?: return Outcome.Err(parseFailed("llm 观测截图失败"))
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
                is Outcome.Ok -> Outcome.Ok(Prepared(base, serializedOf(ctx, got.value, base)))
            }
        }

        private fun record(
            ctx: ToolContext,
            mode: ObserveMode,
            full: Boolean,
            prepared: Prepared,
        ): ObservationResult {
            val history = ctx.deviceHistory()
            val serialized = prepared.serialized
            val observation =
                if (mode == ObserveMode.LLM || full) {
                    serialized.observation
                } else {
                    ScreenJsonDiffer.diff(history.baselines[mode], serialized, serializer.configOf(ctx.config))
                }
            if (mode != ObserveMode.LLM) history.baselines[mode] = serialized
            history.lastTree = serialized.model
            history.lastMode = mode
            return ObservationResult(
                observation = observation,
                ref =
                    ObservationRef(
                        fingerprint = observation.fingerprint,
                        packageName = observation.packageName,
                        activity = observation.activity,
                        summaryLine = observation.summaryLine,
                        nodeCount = serialized.model.quality.nodes,
                        hasImage = false,
                    ),
                screen = serialized,
            )
        }

        /** llm 模型 → 观测文本（a11y/visual 由 [SnapshotSerializer] 直接产出）。 */
        private fun serializedOf(
            ctx: ToolContext,
            model: ScreenModel,
            base: ScreenSnapshot,
        ): SerializedScreen {
            val config = serializer.configOf(ctx.config)
            val suggestion =
                model.quality.suggest?.let {
                    config.renderPrompt(ScreenJsonWire.PROMPT_SUGGEST_MODE, mapOf(VAR_MODE to it.id))
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
                    fingerprint = ScreenFingerprint.of(base.foregroundPackage, base.activity, model.flatten()),
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

        private fun modeUnavailable(
            ctx: ToolContext,
            mode: ObserveMode,
            reason: String,
            effective: List<ObserveMode>,
        ): AppError {
            val others =
                effective
                    .filter { it != mode }
                    .joinToString("、") { it.id }
                    .ifEmpty { "无" }
            val hint =
                serializer.configOf(ctx.config).renderPrompt(
                    ScreenJsonWire.PROMPT_HINT_MODE_UNAVAILABLE,
                    mapOf("mode" to mode.id, "reason" to reason, "others" to others),
                )
            return AppError(
                code = DeviceErrorCodes.MODE_UNAVAILABLE,
                message = "$reason（mode=${mode.id}）",
                hint = hint.takeIf { it.isNotBlank() },
                retryable = false,
            )
        }

        private fun parseFailed(detail: String): AppError =
            AppError(
                code = DeviceErrorCodes.SCREENSHOT_FAILED,
                message = detail,
                retryable = true,
            )

        /** 观测所需的快照与序列化结果。 */
        private class Prepared(
            val snapshot: ScreenSnapshot,
            val serialized: SerializedScreen,
        )

        private companion object {
            const val VAR_MODE: String = "mode"
        }
    }
