/**
 * 上下文组装扩展点（docs/tech/04 §2.4）：按配置 `agent.context.contributors`
 * 的顺序调用各 [ContextContributor]，拼装 system 与首条 user 消息。
 */
package com.example.autop.agent

import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigSnapshot

/** 片段的落位。 */
public enum class Placement {
    SYSTEM_STATIC,
    SYSTEM_DYNAMIC,
    FIRST_USER,
}

/** 上下文组装请求。 */
public data class ContextRequest(
    val runId: RunId,
    val threadId: ThreadId,
    val config: ConfigSnapshot,
    val goal: String?,
    val vars: Map<String, String>,
)

/** 一个上下文片段；[promptIds] 记录用到的模板及其版本（写入 run_started）。 */
public data class ContextPart(
    val placement: Placement,
    val text: String,
    val promptIds: Map<String, Int>,
)

/** 上下文贡献者（扩展点：接口 + DI 多绑定 + 稳定 id）。 */
public interface ContextContributor {
    /** 稳定 id（如 `context.goal`），对应配置中的启用顺序。 */
    public val id: String

    /** 产出片段；返回 null 表示本次不贡献。 */
    public suspend fun contribute(req: ContextRequest): ContextPart?
}
