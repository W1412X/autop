/**
 * 动作收尾（agent-harness-overhaul WP2.3）：等界面稳定 → 按最近观测的选择方式重新观测 →
 * 用观测记录给出的变化结论写效果回执。最近观测是显式 llm 时不再调视觉模型，
 * 改用画面帧签名比较（llm 每个动作都调用成本过高）。
 */
package com.example.autop.device.impl.observation

import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ReplayableAction
import com.example.autop.agent.ScreenDelta
import com.example.autop.agent.ScreenDeltaKind
import com.example.autop.agent.ScreenDeltaReasons
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.ObserveMode
import com.example.autop.device.StableWait
import com.example.autop.device.UiStabilizer
import com.example.autop.device.impl.ObserveSelection
import com.example.autop.device.impl.RecordedObservation
import com.example.autop.device.impl.deviceHistory
import com.example.autop.device.impl.tools.text
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 可回放记录的输入（docs/tech/05 §13）：工具提供参数与实际手势，收尾时补 pre/post 样本。 */
public data class ReplayInput(
    val toolId: String,
    /** 回放参数：坐标原样，另加 `_hit`（§5.7）。 */
    val replayArgs: JsonObject,
    /** execution=gesture 时的实际坐标与时长（屏幕像素）。 */
    val gesture: JsonObject? = null,
)

/** 收尾结果：工具输出与本次收尾观测（dismiss_popup 等复核用；静默/llm 路径为 null）。 */
public data class EpilogueResult(
    val output: ToolOutput,
    val observed: ObservationResult?,
)

/** WRITE_SCREEN 工具 perform 之后的统一收尾。 */
@Singleton
public class ActionEpilogue
    @Inject
    constructor(
        private val stabilizer: UiStabilizer,
        private val observer: ScreenObserver,
        private val probe: ReplayStateProbe,
        private val frameMotion: FrameMotion,
        private val prompts: PromptRepository,
    ) {
        /**
         * 等待界面稳定（[waitMs] 为空取 `device.stabilize.maxWaitMs`）；按最近观测方式取样：
         * null/a11y 用 a11y 快照指纹（便宜），visual/llm 用画面帧签名（WP2.10）。
         */
        public suspend fun awaitStable(
            ctx: ToolContext,
            waitMs: Long? = null,
        ): StableWait {
            val cfg = ctx.config[DeviceConfig.STABILIZE]
            val mode = ctx.deviceHistory().last?.mode
            val sample: suspend () -> String? =
                if (mode == null || mode == ObserveMode.A11Y) {
                    { probe.sample(MODE_A11Y, ctx.config).getOrNull()?.fingerprint }
                } else {
                    { frameMotion.capture()?.hash?.toString() }
                }
            return stabilizer.awaitStable(cfg.quietMs, waitMs ?: cfg.maxWaitMs, cfg.firstEventTimeoutMs, sample)
        }

        /**
         * 收尾：等待界面稳定（[waitMs] 为工具的 `wait_ms`）→ 按最近观测的选择方式重新观测，
         * 用观测记录的变化结论写效果回执。动作成功时填写 [ToolOutput.replay]（只记录成功的调用）。
         */
        public suspend fun finish(
            ctx: ToolContext,
            resultText: String,
            waitMs: Long?,
            replay: ReplayInput,
        ): ToolOutput = finishWithObservation(ctx, resultText, waitMs, replay).output

        /** 同 [finish]，并把收尾观测交给调用方（dismiss_popup 复核弹窗用）。 */
        public suspend fun finishWithObservation(
            ctx: ToolContext,
            resultText: String,
            waitMs: Long?,
            replay: ReplayInput,
        ): EpilogueResult {
            val wait = awaitStable(ctx, waitMs)
            val head = mutableListOf(Block.Text(resultText))
            if (!wait.settled) {
                head += Block.Text(prompts.text(PROMPT_NOT_SETTLED, mapOf(VAR_MS to wait.waitedMs.toString())))
            }
            if (ctx.quietActions) {
                return EpilogueResult(ToolOutput(ok = true, content = head, screenDelta = null, replay = null), null)
            }
            val last = ctx.deviceHistory().last
            val selection = last?.selection ?: ObserveSelection.Auto
            if (selection is ObserveSelection.Explicit && selection.mode == ObserveMode.LLM) {
                return llmFinish(ctx, head, last, replay)
            }
            return when (val observed = observer.observe(ctx, selection, full = false)) {
                is Outcome.Ok -> {
                    val delta = observed.value.delta
                    EpilogueResult(
                        ToolOutput(
                            ok = true,
                            content =
                                head +
                                    observationAndEffect(
                                        observed.value.observation.text,
                                        delta,
                                        replay.toolId,
                                        null,
                                    ),
                            observation = observed.value.ref,
                            screenDelta = delta,
                            replay = buildReplay(replay, preOf(last, ctx.config), observed.value.ref.fingerprint),
                        ),
                        observed.value,
                    )
                }
                is Outcome.Err -> {
                    val delta = ScreenDelta(ScreenDeltaKind.UNKNOWN, ScreenDeltaReasons.OBSERVE_FAILED)
                    EpilogueResult(
                        ToolOutput(
                            ok = true,
                            content = head + effectBlock(delta, replay.toolId, observed.error.code),
                            screenDelta = delta,
                            replay = null,
                        ),
                        null,
                    )
                }
            }
        }

        /** 显式 llm：画面帧签名比较，不调视觉模型、不更新历史、不录回放。 */
        private suspend fun llmFinish(
            ctx: ToolContext,
            head: List<Block>,
            last: RecordedObservation?,
            replay: ReplayInput,
        ): EpilogueResult {
            val post = frameMotion.capture()
            val before = last?.frame
            val delta =
                if (post == null || before == null) {
                    ScreenDelta(ScreenDeltaKind.UNKNOWN, ScreenDeltaReasons.NO_BASELINE)
                } else {
                    val same = frameMotion.same(before, post, ctx.config[DeviceConfig.CHANGE])
                    ScreenDelta(
                        if (same) ScreenDeltaKind.UNCHANGED else ScreenDeltaKind.CHANGED,
                        ScreenDeltaReasons.FRAME,
                    )
                }
            return EpilogueResult(
                ToolOutput(
                    ok = true,
                    content = head + effectBlock(delta, replay.toolId, null),
                    screenDelta = delta,
                    replay = null,
                ),
                null,
            )
        }

        private fun observationAndEffect(
            observationText: String,
            delta: ScreenDelta,
            toolId: String,
            code: String?,
        ): List<Block> = listOf(Block.Text(observationText)) + effectBlock(delta, toolId, code)

        private fun effectBlock(
            delta: ScreenDelta,
            toolId: String,
            code: String?,
        ): List<Block> = listOf(Block.Text(effectLine(delta, toolId, code)))

        /** 效果行（WP2.3 表格口径）：CHANGED/UNCHANGED 带依据，UNKNOWN 带原因。 */
        private fun effectLine(
            delta: ScreenDelta,
            toolId: String,
            code: String?,
        ): String {
            val tool = toolId.removePrefix("tool.")
            return when (delta.kind) {
                ScreenDeltaKind.CHANGED ->
                    prompts.text(PROMPT_EFFECT_CHANGED, mapOf(VAR_BASIS to basisText(delta.reason)))
                ScreenDeltaKind.UNCHANGED ->
                    prompts.text(
                        PROMPT_EFFECT_UNCHANGED,
                        mapOf(VAR_BASIS to basisText(delta.reason), VAR_HINT to hintText(tool)),
                    )
                ScreenDeltaKind.UNKNOWN ->
                    prompts.text(PROMPT_EFFECT_UNKNOWN, mapOf(VAR_REASON to unknownText(delta.reason, code)))
            }
        }

        private fun basisText(reason: String): String = prompts.text(PROMPT_BASIS_PREFIX + reason)

        private fun unknownText(
            reason: String,
            code: String?,
        ): String =
            if (reason == ScreenDeltaReasons.OBSERVE_FAILED) {
                prompts.text(PROMPT_UNKNOWN_OBSERVE_FAILED, mapOf(VAR_CODE to code.orEmpty()))
            } else {
                prompts.text(PROMPT_UNKNOWN_PREFIX + reason)
            }

        private fun hintText(tool: String): String {
            val id = PROMPT_HINT_PREFIX + tool
            return if (id in knownIds) prompts.text(id) else prompts.text(PROMPT_HINT_DEFAULT)
        }

        /** “模板不存在”的判断：一次性取 id 集合缓存，不用 try/catch 吞 get 异常（WP2.3）。 */
        private val knownIds: Set<String> by lazy { prompts.all().mapTo(mutableSetOf()) { it.id } }

        private companion object {
            const val MODE_A11Y = "a11y"

            const val PROMPT_NOT_SETTLED = "device.stabilize.not_settled"
            const val PROMPT_EFFECT_CHANGED = "device.effect.changed"
            const val PROMPT_EFFECT_UNCHANGED = "device.effect.unchanged"
            const val PROMPT_EFFECT_UNKNOWN = "device.effect.unknown"
            const val PROMPT_BASIS_PREFIX = "device.effect.basis."
            const val PROMPT_UNKNOWN_PREFIX = "device.effect.unknown."
            const val PROMPT_UNKNOWN_OBSERVE_FAILED = "device.effect.unknown.observe_failed"
            const val PROMPT_HINT_PREFIX = "device.effect.unchanged_hint."
            const val PROMPT_HINT_DEFAULT = "device.effect.unchanged_hint.default"

            const val VAR_MS = "ms"
            const val VAR_BASIS = "basis"
            const val VAR_HINT = "hint"
            const val VAR_REASON = "reason"
            const val VAR_CODE = "code"
        }
    }

/** 组装可回放记录（docs/tech/05 §13）：pre 缺失（动作前没有可用观测）时不记录。 */
internal fun buildReplay(
    replay: ReplayInput,
    pre: ReplayStateSample?,
    postFingerprint: String?,
): ReplayableAction? {
    val preSample = pre ?: return null
    return ReplayableAction(
        toolId = replay.toolId,
        replayArgs = replay.replayArgs,
        pre = preSample,
        postFingerprint = postFingerprint,
        execution = if (replay.gesture != null) EXEC_GESTURE else EXEC_NODE_ACTION,
        gesture = replay.gesture,
    )
}

/** 动作前样本（WP2.8）：取自收尾前的最近观测；llm 观测不录回放。 */
internal fun preOf(
    last: RecordedObservation?,
    config: ConfigSnapshot,
): ReplayStateSample? = last?.takeIf { it.mode != ObserveMode.LLM }?.let { sampleOf(it.screen, it.mode, config) }

/** 执行方式（docs/tech/05 §13）：无障碍节点动作或手势坐标。 */
private const val EXEC_NODE_ACTION = "node_action"

private const val EXEC_GESTURE = "gesture"
