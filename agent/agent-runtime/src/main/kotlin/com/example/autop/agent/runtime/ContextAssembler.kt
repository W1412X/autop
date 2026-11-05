/**
 * 上下文组装（docs/tech/04 §3.10）：按配置 `agent.context.contributors` 顺序调用贡献者，
 * 生成 system（静态段打缓存标记）与首条 user 片段。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.ContextContributor
import com.example.autop.agent.ContextPart
import com.example.autop.agent.ContextRequest
import com.example.autop.agent.Placement
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.llm.SystemPart

/** 组装结果。 */
internal data class AssembledContext(
    /** system 段：静态（cacheBreakpoint）+ 动态。 */
    val system: List<SystemPart>,
    /** 首条 user 的注入文本（无则 null）。 */
    val firstUser: String?,
    /** 用到的模板版本（写入 run_started）。 */
    val promptIds: Map<String, Int>,
)

/** 上下文组装器。 */
internal class ContextAssembler(
    private val contributors: Set<ContextContributor>,
) {
    private val byId: Map<String, ContextContributor> = contributors.associateBy { it.id }

    suspend fun assemble(
        req: ContextRequest,
        config: ConfigSnapshot,
        extraStatic: String? = null,
    ): AssembledContext {
        val parts =
            config[AgentConfig.CONTEXT].contributors.mapNotNull { id ->
                byId[id]?.contribute(req)
            }
        return build(parts, extraStatic)
    }

    private fun build(
        parts: List<ContextPart>,
        extraStatic: String?,
    ): AssembledContext {
        val static = parts.filter { it.placement == Placement.SYSTEM_STATIC } + listOfNotNull(extraPart(extraStatic))
        val dynamic = parts.filter { it.placement == Placement.SYSTEM_DYNAMIC }
        val firstUser = parts.filter { it.placement == Placement.FIRST_USER }
        val system = mutableListOf<SystemPart>()
        static.joinParts().let { if (it != null) system += SystemPart(text = it, cacheBreakpoint = true) }
        dynamic.joinParts().let { if (it != null) system += SystemPart(text = it, cacheBreakpoint = false) }
        return AssembledContext(
            system = system,
            firstUser = firstUser.joinParts(),
            promptIds = parts.fold(emptyMap()) { acc, part -> acc + part.promptIds },
        )
    }

    private fun extraPart(text: String?): ContextPart? =
        text?.let { ContextPart(placement = Placement.SYSTEM_STATIC, text = it, promptIds = emptyMap()) }

    private fun List<ContextPart>.joinParts(): String? =
        map { it.text }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifEmpty { null }
}
