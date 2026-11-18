/**
 * 事件源查阅工具（docs/tech/07 §7）：`list_trigger_sources` 列出事件源、字段说明与示例值；
 * `sample_events` 查看某个源最近的真实事件字段。两者只读，供写条件任务前确认字段取值。
 */
package com.example.autop.task.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.llm.Block
import com.example.autop.task.FilterOp
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.TriggerCatalog
import com.example.autop.task.TriggerSimulator
import com.example.autop.task.TriggerSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

/** 列出可用事件源：每个源的类型、说明、字段（名称、含义、示例值）与可用操作符。 */
@Singleton
public class ListTriggerSourcesTool
    @Inject
    internal constructor(
        private val sources: Set<@JvmSuppressWildcards TriggerSource>,
        private val catalog: TriggerCatalog,
    ) : Tool {
        override val id: String = "tool.list_trigger_sources"
        override val spec: ToolSpec = TaskToolSpecs.listTriggerSources
        override val access: ToolAccess = ToolAccess.READ_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val body =
                buildJsonObject {
                    put(
                        "sources",
                        buildJsonArray {
                            sources.sortedBy { it.id }.forEach { add(sourceJson(it)) }
                        },
                    )
                    put(
                        "operators",
                        buildJsonObject {
                            FilterOp.entries.forEach { put(it.name, catalog.operators[it.name].orEmpty()) }
                        },
                    )
                }
            return ToolOutput(ok = true, content = listOf(Block.Text(body.toString())), data = body)
        }

        private fun sourceJson(source: TriggerSource): JsonObject {
            val doc = catalog.sources[source.id]
            return buildJsonObject {
                put("id", source.id)
                put("kind", doc?.kind ?: "unknown")
                put("description", doc?.description.orEmpty())
                source.requiresPermission?.let { put("requiresPermission", it) }
                put(
                    "fields",
                    buildJsonArray {
                        source.fieldNames.forEach { name ->
                            val field = doc?.fields?.get(name)
                            add(
                                buildJsonObject {
                                    put("name", name)
                                    put("description", field?.description.orEmpty())
                                    put("example", field?.example.orEmpty())
                                },
                            )
                        }
                    },
                )
            }
        }
    }

/**
 * 查看某个事件源最近的真实事件字段（来自 [TriggerSimulator] 的内存缓冲，目前只有通知源有数据）。
 * 返回内容来自外部，带 `untrusted` 标记。
 */
@Singleton
public class SampleEventsTool
    @Inject
    internal constructor(
        private val sources: Set<@JvmSuppressWildcards TriggerSource>,
        // Provider：模拟器经 TriggerHub 依赖 RunExecutor → AgentSession → 工具集合，直接注入会成环，调用时再取。
        private val simulator: Provider<TriggerSimulator>,
    ) : Tool {
        override val id: String = "tool.sample_events"
        override val spec: ToolSpec = TaskToolSpecs.sampleEvents
        override val access: ToolAccess = ToolAccess.READ_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val sourceId = args.text("source")?.trim().orEmpty()
            if (sources.none { it.id == sourceId }) {
                return failure(
                    TaskErrorCodes.SOURCE_UNKNOWN,
                    "未知事件源：$sourceId",
                    "先调用 list_trigger_sources 查看可用事件源",
                )
            }
            val limit = args.text("limit")?.toIntOrNull()?.coerceIn(1, MAX_SAMPLES) ?: DEFAULT_SAMPLES
            val events = simulator.get().recentEvents(sourceId, limit)
            val body =
                buildJsonObject {
                    put("source", sourceId)
                    put("untrusted", true)
                    put(
                        "events",
                        buildJsonArray {
                            events.forEach { event ->
                                add(
                                    buildJsonObject {
                                        put("occurredAt", event.occurredAt)
                                        put(
                                            "fields",
                                            buildJsonObject { event.fields.forEach { (k, v) -> put(k, v) } },
                                        )
                                    },
                                )
                            }
                        },
                    )
                    if (events.isEmpty()) {
                        put("note", "没有真实事件样例，请参考 list_trigger_sources 中的示例值")
                    }
                }
            return ToolOutput(ok = true, content = listOf(Block.Text(body.toString())), data = body)
        }

        private companion object {
            const val MAX_SAMPLES = 10
            const val DEFAULT_SAMPLES = 5
        }
    }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
