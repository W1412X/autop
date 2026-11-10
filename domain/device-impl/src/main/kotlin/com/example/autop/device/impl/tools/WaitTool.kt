/**
 * `tool.wait`（docs/tech/05 §5）：固定时长等待，或等待文字出现/消失、应用切换。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Clock
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 等待工具。 */
@Singleton
public class WaitTool
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val clock: Clock,
        private val prompts: PromptRepository,
    ) : Tool {
        override val id: String = "tool.wait"

        override val spec = DeviceToolSpecs.wait

        override val access: ToolAccess = ToolAccess.READ_SCREEN

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val ms = args.long("ms")
            val until = args.obj("until")
            val maxMs = maxWaitMs(ctx)
            return when {
                // ms 与 until 同传时按 ms 固定等待（与 spec/模板描述一致）。
                ms != null -> {
                    val waitMs = ms.coerceIn(0, maxMs)
                    delay(waitMs)
                    val truncated = if (waitMs < ms) "（超过上限 $maxMs，已截断）" else ""
                    val ignored = if (until != null) "；同时给了 until，但按固定等待处理，until 未生效" else ""
                    textOutput("已等待 $waitMs 毫秒$truncated$ignored")
                }
                until == null -> toolError(CommonErrorCodes.INVALID_ARGUMENT, "需要 ms 或 until 参数")
                !hasCondition(until) ->
                    toolError(
                        CommonErrorCodes.INVALID_ARGUMENT,
                        "until 至少需要一个条件",
                        hint = "可填 text_appears / text_disappears / package_is 之一",
                    )
                // timeout_ms 写在 until 里是常见误用（模型按 until 的结构套参数），两处都认。
                else -> conditionWait(until, args.long("timeout_ms") ?: until.long("timeout_ms"), ctx)
            }
        }

        /** until 是否给了至少一个条件（空条件会静默空等到超时，N172）。 */
        private fun hasCondition(until: JsonObject): Boolean =
            until.str("text_appears") != null ||
                until.str("text_disappears") != null ||
                until.str("package_is") != null

        /** 等待上限＝工具超时预算留余量，避免被执行器 withTimeout 打成 agent.tool_timeout（N172）。 */
        private fun maxWaitMs(ctx: ToolContext): Long {
            val cfg = ctx.config[AgentConfig.TOOLS]
            return ((cfg.timeoutMs[id] ?: cfg.defaultTimeoutMs) - TIMEOUT_MARGIN_MS).coerceAtLeast(0)
        }

        private suspend fun conditionWait(
            until: JsonObject,
            timeoutMs: Long?,
            ctx: ToolContext,
        ): ToolOutput {
            val waitCfg = ctx.config[DeviceConfig.WAIT]
            val requested = timeoutMs ?: waitCfg.defaultTimeoutMs
            val timeout = requested.coerceIn(0, maxWaitMs(ctx))
            val deadline = clock.nowMillis() + timeout
            val satisfied =
                awaitCondition(
                    until = until,
                    deadline = deadline,
                    pollMs = waitCfg.pollMs,
                    clock = clock,
                    snapshot = { degraded ->
                        if (degraded) screenReader.snapshotOrDegraded() else screenReader.snapshot()
                    },
                )
            val body =
                if (satisfied) {
                    "等待条件已满足"
                } else {
                    "等待超时（$timeout 毫秒），${unsatisfiedReason(until)}"
                }
            // 等待时长被钳制时说明实际用了多少（WP7.10）。
            return textOutput(body + argClampedNote(ARG_TIMEOUT_MS, requested, timeout))
        }

        /** 参数被钳制说明（WP7.10，`agent.tool.arg_clamped`）：没有钳制返回空串。 */
        private fun argClampedNote(
            name: String,
            requested: Long,
            used: Long,
        ): String =
            if (requested == used) {
                ""
            } else {
                "\n" +
                    prompts.text(
                        PROMPT_ARG_CLAMPED,
                        mapOf(VAR_NAME to name, VAR_REQUESTED to requested.toString(), VAR_USED to used.toString()),
                    )
            }

        /** 超时时区分“确实未满足”和“界面压根读不到/文字条件无法判定”，别让模型误以为条件一定是假。 */
        private suspend fun unsatisfiedReason(until: JsonObject): String {
            val needsText = until.str("text_appears") != null || until.str("text_disappears") != null
            return when (val snap = screenReader.snapshot()) {
                is Outcome.Err -> "无法读取当前界面（${snap.error.code}），无法判断条件是否满足"
                is Outcome.Ok ->
                    if (needsText && snap.value.nodes.isEmpty()) {
                        "当前节点树为空，文字条件无法判定；可改用 package_is，或先 observe 视觉/screenshot 确认"
                    } else {
                        "条件未满足"
                    }
            }
        }

        private companion object {
            /** 预留给执行器的余量（毫秒）。 */
            const val TIMEOUT_MARGIN_MS: Long = 5_000L

            const val PROMPT_ARG_CLAMPED = "agent.tool.arg_clamped"
            const val ARG_TIMEOUT_MS = "timeout_ms"
            const val VAR_NAME = "name"
            const val VAR_REQUESTED = "requested"
            const val VAR_USED = "used"
        }
    }

/**
 * until 条件等待（N194）：`package_is` 的前台信息不依赖节点树，判定走降级快照
 * （[snapshot] 传 `degraded = true`）——应用屏蔽无障碍节点（空树）时不再永远
 * 判定未满足直到超时；`text_appears/text_disappears` 依赖节点文本，判定走完整快照
 * （`degraded = false`，采集失败不判满足）。返回条件是否在 [deadline] 前满足。
 */
internal suspend fun awaitCondition(
    until: JsonObject,
    deadline: Long,
    pollMs: Long,
    clock: Clock,
    snapshot: suspend (degraded: Boolean) -> Outcome<ScreenSnapshot>,
): Boolean {
    while (clock.nowMillis() < deadline) {
        if (conditionSatisfied(until, snapshot)) return true
        delay(pollMs)
    }
    return false
}

/**
 * until 条件判定（N194）：package_is 用降级快照（前台不依赖节点树，空树可用）；
 * text 条件用完整快照（依赖节点文本）。给出的条件全部成立才判满足。
 */
internal suspend fun conditionSatisfied(
    until: JsonObject,
    snapshot: suspend (degraded: Boolean) -> Outcome<ScreenSnapshot>,
): Boolean {
    val pkg = until.str("package_is")
    val appear = until.str("text_appears")
    val disappear = until.str("text_disappears")
    val textNeeded = appear != null || disappear != null
    val pkgSnap = if (pkg != null) snapshot(true).getOrNull() else null
    val textSnap = if (textNeeded) snapshot(false).getOrNull() else null
    val pkgMissing = pkg != null && pkgSnap == null
    val textMissing = textNeeded && textSnap == null
    if (pkgMissing || textMissing) return false
    val checks = mutableListOf<Boolean>()
    pkgSnap?.let { checks += it.foregroundPackage == pkg }
    textSnap?.let { snap ->
        appear?.let { checks += hasText(snap, it) }
        disappear?.let { checks += !hasText(snap, it) }
    }
    return checks.isNotEmpty() && checks.all { it }
}

private fun hasText(
    snap: ScreenSnapshot,
    query: String,
): Boolean =
    snap.nodes.any {
        it.text?.contains(query, ignoreCase = true) == true ||
            it.contentDesc?.contains(query, ignoreCase = true) == true
    }
