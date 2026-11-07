@file:Suppress("MagicNumber") // 区域参数的下标

package com.example.autop.flow.impl.ports

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.MessageOrigin
import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolInvoker
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserInteraction
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.core.ObservePort
import com.example.autop.flow.core.Observed
import com.example.autop.flow.core.ToolCallResult
import com.example.autop.flow.core.ToolPort
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.flow.core.screen.ScreenJsonParser
import com.example.autop.llm.Block
import com.example.autop.llm.StopReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** 流程 Run 内共享的值容器。 */
internal class FlowRunScope : RunScopedValues {
    private val values = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST") // 键稳定、类型由调用方约定
    override fun <T : Any> getOrPut(
        key: String,
        create: () -> T,
    ): T = values.getOrPut(key) { create() } as T
}

/** 本次流程运行里产生的截图（引用 → 图片块），供 LLM 节点按引用取图。 */
internal class FlowImages {
    private val images = ConcurrentHashMap<String, Block.Image>()

    fun register(image: Block.Image) {
        images[image.fileRef] = image
    }

    fun get(ref: String): Block.Image? = images[ref]
}

/** 流程内的工具执行上下文：quietActions + origin=FLOW，ask_user 走 UserInteraction。 */
internal class FlowToolContext(
    override val runId: RunId,
    override val threadId: ThreadId,
    override val config: ConfigSnapshot,
    override val threadStore: ThreadStore,
    private val interaction: UserInteraction,
    private val invoker: ToolInvoker,
    private val tag: String? = null,
    private val progress: (String) -> Unit,
) : ToolContext {
    override val runScope: RunScopedValues = FlowRunScope()
    override val tools: ToolInvoker = invoker
    override val quietActions: Boolean = true
    override val origin = com.example.autop.agent.ToolOrigin.FLOW
    override val originTag: String? = tag

    override suspend fun askUser(
        question: String,
        options: List<String>,
        timeoutMs: Long,
    ): Outcome<String> = interaction.ask(runId, question, options, timeoutMs)

    override fun emitProgress(text: String) = progress(text)
}

/** 工具端口：经 `ToolInvoker` 执行，并在 Thread 里留下合成的 ToolCall / ToolResult 事件供时间线展示。 */
internal class ToolPortImpl(
    private val invoker: ToolInvoker,
    private val ctx: ToolContext,
    private val store: ThreadStore,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val accessByName: Map<String, ToolAccess>,
) : ToolPort {
    private val json = AutopJson.default

    override suspend fun call(
        tool: String,
        args: JsonObject,
        nodeId: String,
    ): ToolCallResult {
        val started = clock.nowMillis()
        val out = invoker.invoke(tool, args, ctx)
        record(tool, args, out, clock.nowMillis() - started)
        return toResult(out)
    }

    override fun accessOf(tool: String): ToolAccess? = accessByName[tool]

    private suspend fun record(
        tool: String,
        args: JsonObject,
        out: ToolOutput,
        durationMs: Long,
    ) {
        val callId = "flow_${ids.newId()}"
        store.append(
            ctx.threadId,
            ctx.runId,
            AssistantMessage(
                blocks = listOf(Block.ToolCall(callId, tool, json.encodeToString(JsonObject.serializer(), args))),
                usage = null,
                stopReason = StopReason.TOOL_USE,
                modelProfileId = FLOW_PROFILE,
                origin = MessageOrigin.FLOW,
            ),
        )
        store.append(
            ctx.threadId,
            ctx.runId,
            ToolResultEvent(
                callId = callId,
                toolId = tool,
                ok = out.ok,
                blocks = out.content,
                data = out.data,
                errorCode = out.error?.code,
                observation = out.observation,
                durationMs = durationMs,
            ),
        )
    }

    private fun toResult(out: ToolOutput): ToolCallResult {
        val text = out.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
        val error: AppError? = out.error
        return ToolCallResult(
            ok = out.ok,
            text = text.ifEmpty { error?.message.orEmpty() },
            data = out.data,
            errorCode = error?.code,
            errorHint = error?.hint,
            control = out.control,
        )
    }

    private companion object {
        const val FLOW_PROFILE = "flow"
    }
}

/** 观测端口：经调用器执行 `observe` / `screenshot` 工具并解析统一 JSON。 */
internal class ObservePortImpl(
    private val invoker: ToolInvoker,
    private val ctx: ToolContext,
    private val images: FlowImages,
    private val defaultMode: String,
) : ObservePort {
    override suspend fun observe(mode: String?): Observed {
        val args =
            buildJsonObject {
                put("mode", mode ?: defaultMode)
                put("full", true)
            }
        val out = invoker.invoke(OBSERVE_TOOL, args, ctx)
        val text = out.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
        if (!out.ok) {
            throw FlowException(out.error?.code ?: FlowErrorCodes.INVALID, out.error?.message ?: text)
        }
        return Observed(ScreenJsonParser.parse(text), text)
    }

    override suspend fun screenshot(region: List<Int>?): String {
        val args =
            buildJsonObject {
                put("reason", REASON)
                if (region != null && region.size == REGION_SIZE) {
                    put(
                        "region",
                        buildJsonObject {
                            put("x1", region[0])
                            put("y1", region[1])
                            put("x2", region[2])
                            put("y2", region[3])
                        },
                    )
                }
            }
        val out = invoker.invoke(SCREENSHOT_TOOL, args, ctx)
        if (!out.ok) throw FlowException(out.error?.code ?: FlowErrorCodes.INVALID, out.error?.message.orEmpty())
        val image =
            out.content.filterIsInstance<Block.Image>().firstOrNull()
                ?: throw FlowException(FlowErrorCodes.INVALID, "截图工具没有返回图片")
        images.register(image)
        return image.fileRef
    }

    private companion object {
        const val OBSERVE_TOOL = "observe"
        const val SCREENSHOT_TOOL = "screenshot"
        const val REASON = "flow"
        const val REGION_SIZE = 4
    }
}

/** 把 `JsonArray` 转成字符串列表的小工具。 */
internal fun JsonArray.strings(): List<String> = mapNotNull { (it as? JsonPrimitive)?.content }
