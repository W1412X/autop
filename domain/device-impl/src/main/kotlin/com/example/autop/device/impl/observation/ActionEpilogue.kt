/**
 * 动作收尾（docs/design/tool_optimize/01 §5）：智能体调用按 `device.action.delayMs` 固定等待，
 * 追加等待行，并生成回放记录；流程调用（quietActions）只等待界面稳定。不观测，不判断效果。
 */
package com.example.autop.device.impl.observation

import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ReplayableAction
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Clock
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.ObserveMode
import com.example.autop.device.StableWait
import com.example.autop.device.UiStabilizer
import com.example.autop.device.UserActivityMonitor
import com.example.autop.device.impl.deviceHistory
import com.example.autop.device.impl.tools.text
import com.example.autop.llm.Block
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 可回放记录的输入（docs/tech/05 §13）：工具提供参数与实际手势，收尾时补 pre 样本。 */
public data class ReplayInput(
    val toolId: String,
    /** 回放参数：坐标原样，另加 `_hit`（§5.7）。 */
    val replayArgs: JsonObject,
    /** execution=gesture 时的实际坐标与时长（屏幕像素）。 */
    val gesture: JsonObject? = null,
)

/** WRITE_SCREEN 工具 perform 的统一收尾。 */
@Singleton
@Suppress("LongParameterList") // 装配类：稳定器+回放+帧比较+模板+时钟+触摸监测，不可再减（N114）
public class ActionEpilogue
    @Inject
    constructor(
        private val stabilizer: UiStabilizer,
        private val probe: ReplayStateProbe,
        private val frameMotion: FrameMotion,
        private val prompts: PromptRepository,
        private val clock: Clock,
        private val activityMonitor: UserActivityMonitor,
    ) {
        /** 动作发出前调用：回放录制用的动作前样本；quietActions 时返回 null（流程不录制）。 */
        public suspend fun preSample(ctx: ToolContext): ReplayStateSample? {
            if (ctx.quietActions) return null
            val last = ctx.deviceHistory().last
            return when (last?.mode) {
                // visual 采样要运行本地模型（数秒），不能每个动作做一次：沿用最近一次 visual 观测。
                ObserveMode.VISUAL -> sampleOf(last.screen, ObserveMode.VISUAL, ctx.config)
                // a11y 或尚无观测：实时 a11y 采样（只读，不写观测历史）。
                else -> probe.sample(ObserveMode.A11Y.id, ctx.config).getOrNull()
            }
        }

        /**
         * 两个连续子动作之间的停顿（input_text 点击后、scroll 的步间）：
         * quietActions 时等界面稳定；否则固定等待 [fixedMs]。
         */
        public suspend fun pause(
            ctx: ToolContext,
            fixedMs: Long,
        ) {
            if (ctx.quietActions) {
                awaitStable(ctx, null)
                return
            }
            fixedWait(ctx, fixedMs)
        }

        /**
         * 收尾：等待 → 追加等待行（放在执行记录最后）→ 生成回放记录（只在 pre 非空时）。
         * [waitMs] 为工具的 `wait_ms`；quietActions 等待界面稳定，不追加等待行、不生成回放。
         */
        public suspend fun finish(
            ctx: ToolContext,
            resultText: String,
            waitMs: Long?,
            replay: ReplayInput,
            pre: ReplayStateSample?,
            trace: List<String> = emptyList(),
        ): ToolOutput {
            if (ctx.quietActions) {
                val wait = awaitStable(ctx, waitMs)
                val head = mutableListOf(Block.Text(resultText))
                if (!wait.settled) {
                    head += Block.Text(prompts.text(PROMPT_NOT_SETTLED, mapOf(VAR_MS to wait.waitedMs.toString())))
                }
                return ToolOutput(ok = true, content = head, trace = trace, replay = null)
            }
            val wait = waitOf(ctx, waitMs)
            fixedWait(ctx, wait.ms)
            val waitedLines =
                buildList {
                    add(prompts.text(PROMPT_WAITED, mapOf(VAR_MS to wait.ms.toString())))
                    if (wait.clamped) {
                        add(
                            prompts.text(
                                PROMPT_WAIT_CLAMPED,
                                mapOf(
                                    VAR_MAX to ctx.config[DeviceConfig.ACTION].maxDelayMs.toString(),
                                ),
                            ),
                        )
                    }
                }
            return ToolOutput(
                ok = true,
                content = listOf(Block.Text(resultText)),
                trace = trace + waitedLines,
                replay = pre?.let { buildReplay(replay, it) },
            )
        }

        /**
         * 等待界面稳定（[waitMs] 为空取 `device.stabilize.maxWaitMs`）；按最近观测方式取样：
         * null/a11y 用 a11y 快照指纹（便宜），visual 用画面帧签名。
         * 等待前后登记自身动作窗口，等待期间的触摸不算用户操作（WP7.3）。只供 quietActions 路径。
         */
        private suspend fun awaitStable(
            ctx: ToolContext,
            waitMs: Long? = null,
        ): StableWait {
            val cfg = ctx.config[DeviceConfig.STABILIZE]
            val touch = ctx.config[DeviceConfig.TOUCH]
            val mode = ctx.deviceHistory().last?.mode
            val sample: suspend () -> String? =
                if (mode == null || mode == ObserveMode.A11Y) {
                    { probe.sample(MODE_A11Y, ctx.config).getOrNull()?.fingerprint }
                } else {
                    { frameMotion.capture()?.hash?.toString() }
                }
            val waitStart = clock.nowMillis()
            activityMonitor.markSelfActionWindow(waitStart, waitStart + touch.selfActionMaxMs)
            val wait = stabilizer.awaitStable(cfg.quietMs, waitMs ?: cfg.maxWaitMs, cfg.firstEventTimeoutMs, sample)
            activityMonitor.markSelfActionWindow(waitStart, clock.nowMillis() + touch.selfActionGraceMs)
            return wait
        }

        /** 智能体路径的固定等待：等待前后登记自身动作窗口。 */
        private suspend fun fixedWait(
            ctx: ToolContext,
            ms: Long,
        ) {
            val touch = ctx.config[DeviceConfig.TOUCH]
            val start = clock.nowMillis()
            activityMonitor.markSelfActionWindow(start, start + touch.selfActionMaxMs)
            delay(ms)
            activityMonitor.markSelfActionWindow(start, clock.nowMillis() + touch.selfActionGraceMs)
        }

        private fun waitOf(
            ctx: ToolContext,
            waitMs: Long?,
        ): Wait {
            val cfg = ctx.config[DeviceConfig.ACTION]
            val requested = waitMs ?: cfg.delayMs
            val actual = requested.coerceIn(0, cfg.maxDelayMs)
            return Wait(actual, clamped = actual != requested)
        }

        private data class Wait(
            val ms: Long,
            val clamped: Boolean,
        )

        private companion object {
            const val MODE_A11Y = "a11y"

            const val PROMPT_NOT_SETTLED = "device.stabilize.not_settled"
            const val PROMPT_WAITED = "device.action.waited"
            const val PROMPT_WAIT_CLAMPED = "device.action.wait_clamped"

            const val VAR_MS = "ms"
            const val VAR_MAX = "max"
        }
    }

/** 组装可回放记录（docs/tech/05 §13）：pre 缺失（动作前采样失败）时不记录。 */
internal fun buildReplay(
    replay: ReplayInput,
    pre: ReplayStateSample,
): ReplayableAction =
    ReplayableAction(
        toolId = replay.toolId,
        replayArgs = replay.replayArgs,
        pre = pre,
        postFingerprint = null,
        execution = if (replay.gesture != null) EXEC_GESTURE else EXEC_NODE_ACTION,
        gesture = replay.gesture,
    )

/** 执行方式（docs/tech/05 §13）：无障碍节点动作或手势坐标。 */
private const val EXEC_NODE_ACTION = "node_action"

private const val EXEC_GESTURE = "gesture"
