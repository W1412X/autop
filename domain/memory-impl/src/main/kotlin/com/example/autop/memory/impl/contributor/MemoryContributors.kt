/**
 * 记忆上下文贡献者（docs/tech/06 §3，ADR-0028）：
 * `context.memory_index`（L0 索引）、`context.user_profile`（用户画像）、
 * `context.memory_prefetch`（goal 与触发文本的关键词预取，只注入摘要）。
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
import com.example.autop.memory.SearchHit
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

/** `context.user_profile`：`/profile` 下、来源为用户的条目摘要拼接（SYSTEM_DYNAMIC）。 */
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

/**
 * `context.memory_prefetch`：用 goal 与 `trigger.*` 变量的文本做关键词预取（FIRST_USER）。
 * 只注入每条的路径、标题与一行摘要，总长受 `memory.prefetch.maxChars` 限制；
 * 没有命中就不注入。
 */
@Singleton
public class MemoryPrefetchContributor
    @Inject
    constructor(
        private val search: MemorySearch,
        private val prompts: PromptRepository,
        private val configProvider: ConfigProvider,
    ) : ContextContributor {
        override val id: String = "context.memory_prefetch"

        @Suppress("ReturnCount") // 出口按“无查询文本/无命中/渲染失败”分支，是流程语义
        override suspend fun contribute(req: ContextRequest): ContextPart? {
            val parts = listOfNotNull(req.goal) + req.vars.filterKeys { it.startsWith(TRIGGER_PREFIX) }.values
            val query = parts.joinToString(separator = "\n").trim()
            if (query.isEmpty()) return null
            val cfg = configProvider.current()[MemoryConfig.PREFETCH]
            val hits = search.prefetch(query, cfg.k, cfg.minKeywordScore)
            if (hits.isEmpty()) return null
            val body = clip(hits.map(::oneLine), cfg.maxChars)
            if (body.isEmpty()) return null
            val text =
                when (val r = prompts.render(ID_TEMPLATE, mapOf("hits" to body))) {
                    is Outcome.Ok -> r.value.text
                    is Outcome.Err -> return null
                }
            return ContextPart(
                placement = Placement.FIRST_USER,
                text = text,
                promptIds =
                    runCatching { mapOf(ID_TEMPLATE to prompts.get(ID_TEMPLATE).version) }
                        .getOrDefault(emptyMap()),
            )
        }

        private fun oneLine(hit: SearchHit): String {
            val summary =
                hit.snippet
                    .lineSequence()
                    .firstOrNull()
                    ?.trim()
                    .orEmpty()
            return "${hit.path.value}  ${hit.title}：$summary"
        }

        /** 按行累加，超出总长上限即停止，不截断单行。 */
        private fun clip(
            lines: List<String>,
            maxChars: Int,
        ): String {
            val out = StringBuilder()
            for (line in lines) {
                val piece = if (out.isEmpty()) line else "\n$line"
                if (out.length + piece.length > maxChars) break
                out.append(piece)
            }
            return out.toString()
        }

        private companion object {
            const val ID_TEMPLATE: String = "memory.context.prefetch"
            const val TRIGGER_PREFIX: String = "trigger."
        }
    }
