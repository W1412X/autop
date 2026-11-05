/**
 * L0 索引渲染（docs/tech/06 §2.7）：一级为根目录行（path + summary + 子条数），
 * 预算允许时追加二级标题列表；超出 `maxTokens` 时去掉二级列表。套模板 `memory.context.index`。
 */
package com.example.autop.memory.impl

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.TextTokenEstimator
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.MemoryConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.memory.MemoryIndexRenderer
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.db.MemoryNodeEntity
import javax.inject.Inject
import javax.inject.Singleton

/** L0 索引渲染器。 */
@Singleton
public class MemoryIndexRendererImpl
    @Inject
    constructor(
        private val db: MemoryDb,
        private val prompts: PromptRepository,
        private val configProvider: ConfigProvider,
    ) : MemoryIndexRenderer {
        override suspend fun renderL0(maxTokens: Int): String {
            val cfg = configProvider.current()[MemoryConfig.L0]
            val roots = db.nodeDao().childrenAll(MemoryPath.ROOT.value)
            var body = buildBody(roots, cfg.maxChildrenPerRoot, includeSecondLevel = true)
            if (TextTokenEstimator.estimate(body, CJK_TOKEN_FACTOR, CHARS_PER_TOKEN) > maxTokens) {
                body = buildBody(roots, cfg.maxChildrenPerRoot, includeSecondLevel = false)
            }
            return when (val rendered = prompts.render(ID_INDEX_TEMPLATE, mapOf(VAR_INDEX to body))) {
                is Outcome.Ok -> rendered.value.text
                is Outcome.Err -> body
            }
        }

        private suspend fun buildBody(
            roots: List<MemoryNodeEntity>,
            maxChildrenPerRoot: Int,
            includeSecondLevel: Boolean,
        ): String {
            val lines = mutableListOf<String>()
            for (root in roots) {
                val childCount = db.nodeDao().childCount(root.path)
                lines += "${root.path}/  ${root.summary}（$childCount）"
                if (!includeSecondLevel) continue
                val children = db.nodeDao().childrenAll(root.path)
                children.take(maxChildrenPerRoot).forEach { child ->
                    lines += "  · ${child.title}（${db.nodeDao().childCount(child.path)}）"
                }
                if (children.size > maxChildrenPerRoot) {
                    lines += "  · …还有 ${children.size - maxChildrenPerRoot} 项"
                }
            }
            return lines.joinToString(separator = "\n")
        }

        private companion object {
            const val ID_INDEX_TEMPLATE: String = "memory.context.index"
            const val VAR_INDEX: String = "index"

            /** token 保守估算参数（与 `TextTokenEstimatorTest` 的取值一致）。 */
            const val CJK_TOKEN_FACTOR: Double = 1.5
            const val CHARS_PER_TOKEN: Double = 4.0
        }
    }
