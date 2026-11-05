/**
 * 记忆上下文贡献者（docs/tech/06 §3）：
 * `context.memory_index`（L0 索引）、`context.user_profile`（用户画像）、
 * `context.trigger_memory`（事件触发时预取记忆）。
 */
package com.example.autop.memory.impl.contributor

import com.example.autop.agent.ContextContributor
import com.example.autop.agent.ContextPart
import com.example.autop.agent.ContextRequest
import com.example.autop.agent.Placement
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.MemoryConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.memory.MemoryIndexRenderer
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySearch
import com.example.autop.memory.MemoryStore
import javax.inject.Inject
import javax.inject.Singleton

/** `context.memory_index`：L0 目录索引（SYSTEM_DYNAMIC）。 */
@Singleton
public class MemoryIndexContributor
    @Inject
    constructor(
        private val renderer: MemoryIndexRenderer,
        private val prompts: PromptRepository,
        private val configProvider: ConfigProvider,
    ) : ContextContributor {
        override val id: String = "context.memory_index"

        override suspend fun contribute(req: ContextRequest): ContextPart? {
            val maxTokens = configProvider.current()[MemoryConfig.L0].maxTokens
            val text = renderer.renderL0(maxTokens)
            return ContextPart(placement = Placement.SYSTEM_DYNAMIC, text = text, promptIds = versionOf(ID_TEMPLATE))
        }

        private fun versionOf(promptId: String): Map<String, Int> =
            runCatching { mapOf(promptId to prompts.get(promptId).version) }.getOrDefault(emptyMap())

        private companion object {
            const val ID_TEMPLATE: String = "memory.context.index"
        }
    }

/** `context.user_profile`：`/profile` 下各节点 summary 拼接（SYSTEM_DYNAMIC）。 */
@Singleton
public class UserProfileContributor
    @Inject
    constructor(
        private val store: MemoryStore,
        private val prompts: PromptRepository,
        private val configProvider: ConfigProvider,
    ) : ContextContributor {
        override val id: String = "context.user_profile"

        @Suppress("ReturnCount") // 出口按“无画像/渲染失败”分支，是流程语义
        override suspend fun contribute(req: ContextRequest): ContextPart? {
            val maxTokens = configProvider.current()[MemoryConfig.PROFILE].maxTokens
            val nodes = store.children(MemoryPath.ROOT.child("profile"), offset = 0, limit = PROFILE_LIMIT)
            if (nodes.isEmpty()) return null
            val body = nodes.joinToString(separator = "\n") { "· ${it.title}：${it.summary}" }
            val budgetChars = maxTokens * CHARS_PER_TOKEN
            val clipped = if (body.length > budgetChars) body.take(budgetChars) else body
            val text = render(clipped) ?: return null
            return ContextPart(
                placement = Placement.SYSTEM_DYNAMIC,
                text = text,
                promptIds =
                    runCatching { mapOf(ID_TEMPLATE to prompts.get(ID_TEMPLATE).version) }
                        .getOrDefault(emptyMap()),
            )
        }

        private fun render(profile: String): String? =
            when (val r = prompts.render(ID_TEMPLATE, mapOf("profile" to profile))) {
                is Outcome.Ok -> r.value.text
                is Outcome.Err -> null
            }

        private companion object {
            const val ID_TEMPLATE: String = "memory.context.profile"
            const val PROFILE_LIMIT: Int = 20

            /** 预算换算：约 1 token ≈ 4 字的保守估算。 */
            const val CHARS_PER_TOKEN: Int = 4
        }
    }

/** `context.trigger_memory`：Run 变量含 `trigger.*` 时预取相关记忆（FIRST_USER）。 */
@Singleton
public class TriggerMemoryContributor
    @Inject
    constructor(
        private val search: MemorySearch,
        private val prompts: PromptRepository,
        private val configProvider: ConfigProvider,
    ) : ContextContributor {
        override val id: String = "context.trigger_memory"

        @Suppress("ReturnCount") // 出口按“无 trigger/无命中/渲染失败”分支，是流程语义
        override suspend fun contribute(req: ContextRequest): ContextPart? {
            val triggerText =
                req.vars
                    .filterKeys { it.startsWith("trigger.") }
                    .values
                    .joinToString(separator = "\n")
                    .ifBlank { return null }
            val k = configProvider.current()[MemoryConfig.TRIGGER_PREFETCH].k
            val hits = search.search(query = triggerText, prefix = null, k = k)
            if (hits.isEmpty()) return null
            val body = hits.joinToString(separator = "\n") { "${it.path.value}  ${it.title} — ${it.snippet}" }
            val rendered =
                when (val r = prompts.render(ID_TEMPLATE, mapOf("hits" to body))) {
                    is Outcome.Ok -> r.value.text
                    is Outcome.Err -> return null
                }
            return ContextPart(
                placement = Placement.FIRST_USER,
                text = rendered,
                promptIds =
                    runCatching { mapOf(ID_TEMPLATE to prompts.get(ID_TEMPLATE).version) }
                        .getOrDefault(emptyMap()),
            )
        }

        private companion object {
            const val ID_TEMPLATE: String = "memory.context.trigger_prefetch"
        }
    }
