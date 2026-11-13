/**
 * 记忆读工具（docs/tech/06 §3）：`tool.memory_list`、`tool.memory_read`、`tool.memory_search`。
 *
 * 返回的记忆正文不包不可信信封（用户与 agent 自己写的数据）。
 */
package com.example.autop.memory.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.MemoryConfig
import com.example.autop.memory.MemoryErrorCodes
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySearch
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** `tool.memory_list`：列出记忆目录。 */
@Singleton
public class MemoryListTool
    @Inject
    constructor(
        private val store: MemoryStore,
        private val configProvider: ConfigProvider,
    ) : Tool {
        override val id: String = "tool.memory_list"

        override val spec = MemoryToolSpecs.memoryList

        override val access: ToolAccess = ToolAccess.READ_MEMORY

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val path = parsePath(args.str("path").orEmpty()) ?: return pathError()
            val maxDepth = configProvider.current()[MemoryConfig.LIST].maxDepth
            val depth = (args.intOrNull("depth") ?: 1).coerceIn(1, maxDepth)
            val offset = (args.intOrNull("offset") ?: 0).coerceAtLeast(0)
            val limit = (args.intOrNull("limit") ?: DEFAULT_LIMIT).coerceAtLeast(1)
            val lines = listLines(store, path, depth, offset, limit)
            return textOutput(lines.joinToString(separator = "\n").ifEmpty { "（空目录）" })
        }
    }

/** `tool.memory_read`：读取记忆正文（分页）。 */
@Singleton
public class MemoryReadTool
    @Inject
    constructor(
        private val store: MemoryStore,
    ) : Tool {
        override val id: String = "tool.memory_read"

        override val spec = MemoryToolSpecs.memoryRead

        override val access: ToolAccess = ToolAccess.READ_MEMORY

        @Suppress("ReturnCount") // 出口按“路径非法/不存在”错误码分支，是业务语义
        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val path = parsePath(args.str("path").orEmpty()) ?: return pathError()
            val node =
                store.get(path)
                    ?: return toolError(MemoryErrorCodes.NOT_FOUND, "路径不存在：${path.value}")
            val offset = (args.intOrNull("offset") ?: 0).coerceAtLeast(0)
            val limit = (args.intOrNull("limit") ?: DEFAULT_PAGE_CHARS).coerceAtLeast(0)
            val end = (offset + limit).coerceAtMost(node.content.length)
            val page = if (offset >= node.content.length) "" else node.content.substring(offset, end)
            val meta =
                buildString {
                    appendLine("标题：${node.title}")
                    appendLine("来源：${sourceLabel(node.source)}")
                    if (node.keywords.isNotEmpty()) appendLine("关键词：${node.keywords.joinToString("、")}")
                    if (node.links.isNotEmpty()) appendLine("关联：${node.links.joinToString("、")}")
                    appendLine()
                }
            return textOutput(meta + page + "\n（共 ${node.content.length} 字，已显示 $offset–$end）")
        }
    }

/** `tool.memory_search`：混合检索记忆。 */
@Singleton
public class MemorySearchTool
    @Inject
    constructor(
        private val search: MemorySearch,
        private val configProvider: ConfigProvider,
    ) : Tool {
        override val id: String = "tool.memory_search"

        override val spec = MemoryToolSpecs.memorySearch

        override val access: ToolAccess = ToolAccess.READ_MEMORY

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val query = args.str("query").orEmpty()
            if (query.isBlank()) return toolError("agent.invalid_args", "query 不能为空")
            val prefix = args.str("path_prefix")?.let { parsePath(it) }
            val k = args.intOrNull("k") ?: configProvider.current()[MemoryConfig.SEARCH].defaultK
            val hits = search.search(query = query, prefix = prefix, k = k)
            val text =
                if (hits.isEmpty()) {
                    "（没有匹配的记忆）"
                } else {
                    hits.joinToString(separator = "\n") { hit ->
                        "${hit.path.value}  [${sourceLabel(hit.source)}] ${hit.title}：${hit.snippet}"
                    }
                }
            return textOutput(text)
        }
    }

internal const val DEFAULT_LIMIT: Int = 20

internal const val DEFAULT_PAGE_CHARS: Int = 3000

internal fun parsePath(raw: String): MemoryPath? =
    when (val r = MemoryPath.parse(raw)) {
        is Outcome.Ok -> r.value
        is Outcome.Err -> null
    }

internal fun pathError(): ToolOutput = toolError(MemoryErrorCodes.INVALID_PATH, "路径非法")

/** 条目来源的展示标签（读工具输出区分用户/agent/流程写入）。 */
internal fun sourceLabel(source: MemorySource): String =
    when (source) {
        MemorySource.USER -> "用户"
        MemorySource.AGENT -> "agent"
        MemorySource.FLOW -> "流程"
        MemorySource.IMPORT -> "导入"
    }

internal suspend fun listLines(
    store: MemoryStore,
    path: MemoryPath,
    depth: Int,
    offset: Int,
    limit: Int,
): List<String> {
    val out = mutableListOf<String>()
    collectChildren(store, store.children(path, offset, limit), depth - 1, limit, out)
    return out
}

private suspend fun collectChildren(
    store: MemoryStore,
    nodes: List<com.example.autop.memory.MemoryNode>,
    depth: Int,
    limit: Int,
    out: MutableList<String>,
) {
    for (child in nodes) {
        if (out.size >= limit) return
        out +=
            "${child.path.value}  ${child.title} — ${child.summary}（${child.childCount}）" +
            "[${sourceLabel(child.source)}]"
        if (depth > 0) {
            collectChildren(store, store.children(child.path, 0, limit), depth - 1, limit, out)
        }
    }
}
