/**
 * 动作收尾（docs/design/observation-modes-coordinate-actions §5.5）：
 * `awaitStable` → 按本 Run 最近观测模式补一次差量观测（llm 模式只给变化位）→
 * 组装 `ToolOutput`（含可回放记录，R1/A6）。不再附截图。
 */
package com.example.autop.device.impl.observation

import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ReplayableAction
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.ObserveMode
import com.example.autop.device.UiStabilizer
import com.example.autop.device.impl.deviceHistory
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** 可回放记录的输入（docs/tech/05 §13）：工具提供参数与实际手势，收尾时补 pre/post 指纹。 */
public data class ReplayInput(
    val toolId: String,
    /** 动作执行前的屏幕样本（[ActionEpilogue.begin] 采样）；为 null 时不记录回放。 */
    val pre: ReplayStateSample?,
    /** 回放参数：坐标原样，另加 `_hit`（§5.7）。 */
    val replayArgs: JsonObject,
    /** execution=gesture 时的实际坐标与时长（屏幕像素）。 */
    val gesture: JsonObject? = null,
)

/** WRITE_SCREEN 工具 perform 之后的统一收尾。 */
@Singleton
public class ActionEpilogue
    @Inject
    constructor(
        private val stabilizer: UiStabilizer,
        private val observer: ScreenObserver,
        private val probe: ReplayStateProbe,
    ) {
        /**
         * 动作执行前采样（docs/tech/05 §13 的 `pre`）；失败返回 null。
         * [ToolContext.quietActions] 为真（流程）时不采样：流程不录制回放，省掉一次完整采集。
         */
        public suspend fun begin(ctx: ToolContext): ReplayStateSample? =
            if (ctx.quietActions) null else probe.sample().getOrNull()

        /** 当前屏幕指纹（采样失败为 null）；工具用它判断动作是否产生了变化。 */
        public suspend fun fingerprintNow(): String? = probe.sample().getOrNull()?.fingerprint

        /** 等待界面稳定（[waitMs] 为空取 `device.stabilize.maxWaitMs`）；工具可单独调用。 */
        public suspend fun awaitStable(
            ctx: ToolContext,
            waitMs: Long? = null,
        ) {
            val cfg = ctx.config[DeviceConfig.STABILIZE]
            stabilizer.awaitStable(cfg.quietMs, waitMs ?: cfg.maxWaitMs, cfg.firstEventTimeoutMs) {
                probe.sample().getOrNull()?.fingerprint
            }
        }

        /**
         * 收尾：等待界面稳定（[waitMs] 为工具的 `wait_ms`）→ a11y/visual 按最近观测模式
         * 出差量观测；llm 只附一行变化位。动作成功时填写 [ToolOutput.replay]（只记录成功的调用）。
         */
        public suspend fun finish(
            ctx: ToolContext,
            resultText: String,
            waitMs: Long?,
            replay: ReplayInput,
        ): ToolOutput {
            awaitStable(ctx, waitMs)
            if (ctx.quietActions) {
                return ToolOutput(
                    ok = true,
                    content = listOf(Block.Text(resultText)),
                    replay = buildReplay(replay, null),
                )
            }
            val mode = ctx.deviceHistory().lastMode ?: ObserveMode.A11Y
            if (mode == ObserveMode.LLM) {
                val post = probe.sample().getOrNull()
                val changed =
                    post != null && replay.pre != null && post.fingerprint != replay.pre.fingerprint
                val line =
                    buildJsonObject {
                        put("screen_changed", changed)
                        put("app", post?.packageName.orEmpty())
                        put("page", post?.activity?.substringAfterLast('.').orEmpty())
                    }.toString()
                return ToolOutput(
                    ok = true,
                    content = listOf(Block.Text(resultText), Block.Text(line)),
                    replay = buildReplay(replay, post?.fingerprint),
                )
            }
            return when (val observed = observer.observe(ctx, mode)) {
                is Outcome.Ok ->
                    ToolOutput(
                        ok = true,
                        content = listOf(Block.Text(resultText), Block.Text(observed.value.observation.text)),
                        observation = observed.value.ref,
                        replay = buildReplay(replay, observed.value.ref.fingerprint),
                    )
                is Outcome.Err ->
                    ToolOutput(
                        ok = true,
                        content = listOf(Block.Text(resultText), Block.Text("屏幕观测失败：${observed.error.code}")),
                        replay = buildReplay(replay, postFingerprint = null),
                    )
            }
        }
    }

/** 组装可回放记录（docs/tech/05 §13）：pre 缺失（执行前采样失败）时不记录。 */
internal fun buildReplay(
    replay: ReplayInput,
    postFingerprint: String?,
): ReplayableAction? {
    val pre = replay.pre ?: return null
    return ReplayableAction(
        toolId = replay.toolId,
        replayArgs = replay.replayArgs,
        pre = pre,
        postFingerprint = postFingerprint,
        execution = if (replay.gesture != null) EXEC_GESTURE else EXEC_NODE_ACTION,
        gesture = replay.gesture,
    )
}

/** 执行方式（docs/tech/05 §13）：无障碍节点动作或手势坐标。 */
private const val EXEC_NODE_ACTION = "node_action"

private const val EXEC_GESTURE = "gesture"
