/**
 * 经验提炼端口（docs/design/agent-design.md §8 App 手册自我进化、docs/tech/09 §2
 * 模板 `memory.experience.extract`）：Run 成功收口后把本次步骤的经验增量追加写入
 * `/apps/{package}/_manual`（版本与撤销同 memory_write）。实现见 memory-impl。
 */
package com.example.autop.memory

import com.example.autop.core.common.RunId

/** 经验提炼。 */
public interface ExperienceExtractor {
    /**
     * 提炼 [request] 描述的 Run 经验并写入 App 手册。
     *
     * @return 是否写入了手册（`features.experienceExtraction` 关闭、模型判定无需
     *   补充或执行失败时为 false，失败原因记日志）。
     */
    public suspend fun extract(request: ExperienceRequest): Boolean
}

/** 一次经验提炼请求。 */
public data class ExperienceRequest(
    /** 来源 Run（写入版本记录，可按 Run 撤销）。 */
    val runId: RunId,
    /** 本次 Run 最后涉及的应用包名（写入 `/apps/{package}/_manual`）。 */
    val packageName: String,
    /** 本次 Run 的步骤摘要（逐行文本）。 */
    val steps: String,
)
