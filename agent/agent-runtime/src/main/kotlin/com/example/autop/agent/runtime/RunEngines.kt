/**
 * 每个 Run 组装一次的运行期引擎与端口（docs/tech/04 §3.1）：
 * 预算、熔断、压缩、笔记与模型/模板/日志端口。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayTargetResolver
import com.example.autop.agent.UserInteraction
import com.example.autop.agent.runtime.compaction.CompactionCoordinator
import com.example.autop.agent.runtime.guard.Budget
import com.example.autop.agent.runtime.guard.CircuitBreaker
import com.example.autop.agent.runtime.notes.RunNotes
import com.example.autop.agent.runtime.state.RuntimeStateTracker
import com.example.autop.core.common.Logger
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.LlmGateway

/** Run 间共享的服务端口。 */
internal class RunPorts(
    val llm: LlmGateway,
    val prompts: PromptRepository,
    val logger: Logger,
    /** ask_user 端口；未绑定时按超时处理（docs/tech/04 §3.9）。 */
    val interaction: UserInteraction?,
    /** 回放前状态采样端口；未绑定时跳过回放前置比对（docs/tech/04 §3.12）。 */
    val replayProbe: ReplayStateProbe?,
    /** 回放目标重解析端口；未绑定时按记录坐标执行（§5.7）。 */
    val replayTargets: ReplayTargetResolver?,
)

/** 每个 Run 独立的引擎（含运行期计数状态）。 */
internal class RunEngines(
    val budget: Budget,
    val breaker: CircuitBreaker,
    val compaction: CompactionCoordinator,
    val notes: RunNotes,
    val state: RuntimeStateTracker,
    val ports: RunPorts,
)
