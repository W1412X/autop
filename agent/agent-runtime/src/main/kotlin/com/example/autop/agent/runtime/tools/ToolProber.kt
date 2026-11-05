/**
 * [ToolProbeService] 实现（N198）：直接调用工具本身（普通 [Tool.invoke]，屏幕动作
 * 两步 prepare→perform），不走 ToolRouter 的锁与策略——人工自测入口，绕开 Run 编排。
 * 每个工具一份内置默认测试参数（[PROBE_FIXTURES]，新增工具须补），面板可编辑后传回。
 * 参数元数据由参数 Schema + 模板 `meta["param.<名>"]` 说明 + fixture 示例组装（N207）。
 * 探测历史留在内存（[MAX_HISTORY]），导出时连同应用运行日志一起给出（定位用）。
 */
package com.example.autop.agent.runtime.tools

import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolParamKind
import com.example.autop.agent.ToolProbeParam
import com.example.autop.agent.ToolProbeResult
import com.example.autop.agent.ToolProbeService
import com.example.autop.agent.ToolProbeTarget
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.LogFiles
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.runCatchingCancellable
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** 探测历史条数上限（更早的记录被裁掉，导出仍看得到最近现场）。 */
private const val MAX_HISTORY: Int = 50

/** 探测用的 Run id（无真实 Run，仅满足 ToolContext 形状）。 */
private const val PROBE_RUN_ID: String = "tool-probe"

/** 探测用线程标题（懒创建一条真实线程，note / history_recall 等工具可正常落记录）。 */
private const val PROBE_THREAD_TITLE: String = "工具自测"

public class ToolProber(
    private val tools: List<Tool>,
    private val threadStore: ThreadStore,
    private val config: ConfigProvider,
    private val clock: Clock,
    private val logFiles: LogFiles,
    private val prompts: PromptRepository,
) : ToolProbeService {
    private val byName: Map<String, Tool> = tools.associateBy { it.spec.name }
    private val json = Json { ignoreUnknownKeys = true }
    private val results = mutableListOf<ToolProbeResult>()
    private var probeThread: ThreadId? = null

    override fun targets(): List<ToolProbeTarget> =
        tools.map { tool ->
            ToolProbeTarget(
                name = tool.spec.name,
                access = tool.access,
                defaultArgs = fixtureFor(tool.spec.name).toString(),
                actsOnScreen = tool.spec.name in ACTS_ON_SCREEN,
                params = paramsFor(tool),
            )
        }

    override suspend fun probe(
        toolName: String,
        argsJson: String?,
    ): ToolProbeResult {
        val tool = byName[toolName]
        if (tool == null) {
            return record(
                ToolProbeResult(
                    toolName = toolName,
                    args = argsJson.orEmpty(),
                    ok = false,
                    durationMs = 0,
                    summary = "未知工具（注册名：${byName.keys.sorted()}）",
                    atMillis = clock.nowMillis(),
                ),
            )
        }
        val args = parseArgs(argsJson) ?: fixtureFor(toolName)
        val start = clock.nowMillis()
        val output = runTool(tool, args, probeContext())
        return record(
            ToolProbeResult(
                toolName = toolName,
                args = args.toString(),
                ok = output.ok,
                durationMs = clock.nowMillis() - start,
                summary = probeSummaryOf(output),
                atMillis = clock.nowMillis(),
                images = imagesOf(output),
            ),
        )
    }

    override fun history(): List<ToolProbeResult> = synchronized(results) { results.toList() }

    override fun diagnosticsText(): String =
        buildString {
            appendLine("# Autop 工具自测诊断导出（N198）")
            appendLine("# 探测 ${history().size} 条；时间戳 ${clock.nowMillis()}")
            appendLine("== 工具自测记录（旧→新）==")
            history().forEach { appendLine(probeLineOf(it)) }
            appendLine("== 应用运行日志（files/logs，旧→新）==")
            append(logFiles.readAll())
        }

    /** 执行工具：屏幕动作两步 prepare→perform，其余 invoke；异常转错误结果。 */
    private suspend fun runTool(
        tool: Tool,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput =
        runCatchingCancellable(code = "agent.tool_crashed") {
            when (tool) {
                is ScreenActionTool -> performScreenAction(tool, args, ctx)
                else -> tool.invoke(args, ctx)
            }
        }.let { outcome ->
            when (outcome) {
                is Outcome.Ok -> outcome.value
                is Outcome.Err -> ToolOutput(ok = false, content = emptyList(), error = outcome.error)
            }
        }

    /** 屏幕动作的两步执行（ToolRouter 分两步做策略检查，自测直接连起来）。 */
    private suspend fun performScreenAction(
        tool: ScreenActionTool,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput =
        when (val prepared = tool.prepare(args, ctx)) {
            is Outcome.Ok -> tool.perform(prepared.value, ctx)
            is Outcome.Err -> ToolOutput(ok = false, content = emptyList(), error = prepared.error)
        }

    /** 参数解析：null / 非法 JSON 回退默认 fixture（面板输入坏了也能跑）。 */
    private fun parseArgs(argsJson: String?): JsonObject? =
        runCatching { argsJson?.trim()?.takeIf { it.isNotEmpty() }?.let { json.parseToJsonElement(it) as? JsonObject } }
            .getOrNull()

    private fun fixtureFor(toolName: String): JsonObject = PROBE_FIXTURES[toolName] ?: JsonObject(emptyMap())

    /** 参数元数据（N207）：Schema 定结构，模板 meta 给说明，fixture 当示例。 */
    private fun paramsFor(tool: Tool): List<ToolProbeParam> {
        val props =
            tool.spec.parameters["properties"]
                ?.jsonObject
                .orEmpty()
        if (props.isEmpty()) return emptyList()
        val requiredKeys =
            tool.spec.parameters["required"]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .toSet()
        val meta = runCatching { prompts.get(tool.spec.descriptionPromptId).meta }.getOrDefault(emptyMap())
        val fixture = fixtureFor(tool.spec.name)
        return props.map { (name, schema) ->
            paramOf(
                name = name,
                schema = schema.jsonObject,
                required = name in requiredKeys,
                doc = meta["param.$name"].orEmpty(),
                example = fixture[name],
            )
        }
    }

    private fun paramOf(
        name: String,
        schema: JsonObject,
        required: Boolean,
        doc: String,
        example: JsonElement?,
    ): ToolProbeParam {
        val kind = kindOf(schema["type"]?.jsonPrimitive?.contentOrNull)
        return ToolProbeParam(
            name = name,
            kind = kind,
            required = required,
            enumValues = schema["enum"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            description = doc + nestedHintOf(kind, schema),
            example = exampleTextOf(example),
        )
    }

    private fun record(result: ToolProbeResult): ToolProbeResult {
        synchronized(results) {
            results += result
            while (results.size > MAX_HISTORY) results.removeAt(0)
        }
        return result
    }

    /** 探测上下文：懒创建真实探测线程（note / history_recall 可落记录），无交互端口。 */
    private suspend fun probeContext(): ToolContext {
        val thread = probeThread ?: threadStore.createThread(PROBE_THREAD_TITLE, null).also { probeThread = it }
        return ProbeToolContext(thread, config.current(), threadStore)
    }
}

/** 输出摘要：错误优先，其次文本/结构化数据；附控制指令标记（完整保留，展示端折叠；N207）。 */
private fun probeSummaryOf(output: ToolOutput): String {
    val error = output.error
    val text = output.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
    val head =
        when {
            error != null -> "错误 ${error.code}: ${error.message}"
            text.isNotBlank() -> text
            output.data != null -> output.data.toString()
            else -> "(无文本输出)"
        }
    val control = output.control?.let { " [控制指令 ${it::class.simpleName}]" }.orEmpty()
    return head + control
}

/** 提取附图（N208）：调试图/截图等 Block.Image 从输出内容抽出，给面板/记录页点选查看。 */
private fun imagesOf(output: ToolOutput): List<Block.Image> = output.content.filterIsInstance<Block.Image>()

/** Schema `type` → 参数控件类型（缺省按字符串）。 */
private fun kindOf(type: String?): ToolParamKind =
    when (type) {
        "integer" -> ToolParamKind.INTEGER
        "number" -> ToolParamKind.NUMBER
        "boolean" -> ToolParamKind.BOOLEAN
        "object" -> ToolParamKind.OBJECT
        "array" -> ToolParamKind.ARRAY
        else -> ToolParamKind.STRING
    }

/** 嵌套参数的子字段提示（拼到说明后面；子字段本身无 meta 说明，给名字与类型即可定位）。 */
private fun nestedHintOf(
    kind: ToolParamKind,
    schema: JsonObject,
): String {
    val fields =
        when (kind) {
            ToolParamKind.OBJECT -> schema["properties"]?.jsonObject.orEmpty()
            ToolParamKind.ARRAY ->
                schema["items"]
                    ?.jsonObject
                    ?.get("properties")
                    ?.jsonObject
                    .orEmpty()
            else -> emptyMap()
        }
    if (fields.isEmpty()) return ""
    val listed =
        fields.entries.joinToString("、") { (name, child) ->
            val childType = child.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "string"
            "$name($childType)"
        }
    return if (kind == ToolParamKind.ARRAY) "（元素字段：$listed）" else "（子字段：$listed）"
}

/** 示例值文本：字符串去引号（可直接回填编辑框），嵌套值给 JSON 文本。 */
private fun exampleTextOf(example: JsonElement?): String =
    when (val primitive = example as? JsonPrimitive) {
        null -> example?.toString().orEmpty()
        else -> primitive.contentOrNull ?: primitive.toString()
    }

/** 探测记录的导出行（单行 JSON，与对话事件导出同款风格）。 */
private fun probeLineOf(result: ToolProbeResult): String {
    val images = result.images.joinToString(",") { jsonStr(it.fileRef) }
    return """{"tool":${jsonStr(result.toolName)},"ok":${result.ok},"durationMs":${result.durationMs},""" +
        """"at":${result.atMillis},"args":${jsonStr(result.args)},"summary":${jsonStr(result.summary)},""" +
        """"images":[$images]}"""
}

/** JSON 字符串字面量（带引号与转义）。 */
private fun jsonStr(text: String): String = Json.encodeToString(kotlinx.serialization.serializer<String>(), text)

/** 工具自测上下文（N198）：固定 runId + 探测线程，ask_user 直接报无交互端口。 */
private class ProbeToolContext(
    override val threadId: ThreadId,
    private val snapshot: ConfigSnapshot,
    private val store: ThreadStore,
) : ToolContext {
    override val runId: RunId = RunId(PROBE_RUN_ID)

    override val config: ConfigSnapshot get() = snapshot

    override val runScope: RunScopedValues = ProbeRunScope()

    override val threadStore: ThreadStore get() = store

    override val debugImages: Boolean get() = true

    override suspend fun askUser(
        question: String,
        options: List<String>,
        timeoutMs: Long,
    ): Outcome<String> = Outcome.Err(AppError(code = "probe.no_interaction", message = "工具自测无交互端口：$question"))

    override fun emitProgress(text: String): Unit = Unit
}

/** 探测的运行期共享值容器（同 RunScopeValues 语义，独立实例避免串场）。 */
private class ProbeRunScope : RunScopedValues {
    private val values = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST") // 键稳定、类型由调用方约定（同 RuntimeToolContext）
    override fun <T : Any> getOrPut(
        key: String,
        create: () -> T,
    ): T = values.getOrPut(key) { create() } as T
}

/** 会真实操作屏幕的工具名（UI 标注 + 执行时面板让路）。 */
private val ACTS_ON_SCREEN: Set<String> =
    setOf("tap", "input_text", "scroll", "swipe", "global_action", "open_app", "open_uri", "clipboard_set")

/**
 * 各工具的默认测试参数（键为 spec.name，N198）。参数名与 toolSpec DSL 一一对应
 * （DeviceToolSpecs / MemoryToolSpecs / 控制工具 spec）；新增工具须在此补条目，
 * 缺失时回退空对象（参数不合法会得到错误结果，也是有效诊断输出）。
 */
private val PROBE_FIXTURES: Map<String, JsonObject> =
    mapOf(
        "observe" to jsonObjectOf("mode" to "a11y", "full" to true),
        "screenshot" to jsonObjectOf("reason" to "工具自测（N198）"),
        "tap" to jsonObjectOf("x" to 500, "y" to 500),
        "input_text" to jsonObjectOf("text" to "autop 自测"),
        "scroll" to jsonObjectOf("direction" to "down"),
        "swipe" to jsonObjectOf("x1" to 500, "y1" to 700, "x2" to 500, "y2" to 300, "duration_ms" to 300),
        "global_action" to jsonObjectOf("action" to "notifications"),
        "open_app" to jsonObjectOf("package" to "com.android.settings"),
        "open_uri" to jsonObjectOf("uri" to "https://example.com"),
        "wait" to jsonObjectOf("ms" to 200),
        "list_apps" to jsonObjectOf("query" to "设置"),
        "get_device_state" to jsonObjectOf(),
        "clipboard_get" to jsonObjectOf(),
        "clipboard_set" to jsonObjectOf("text" to "autop-probe"),
        "read_notifications" to jsonObjectOf(),
        "memory_list" to jsonObjectOf("path" to "/"),
        "memory_read" to jsonObjectOf("path" to "/"),
        "memory_search" to jsonObjectOf("query" to "自测"),
        "memory_write" to
            jsonObjectOf(
                "path" to "/probe/n198",
                "mode" to "CREATE",
                "content" to "工具自测条目（N198）",
                "reason" to "工具自测",
            ),
        "update_plan" to """{"items":[{"step":"工具自测","status":"pending"}]}""".let(::jsonElementOf),
        "finish" to jsonObjectOf("status" to "success", "summary" to "工具自测"),
        "ask_user" to jsonObjectOf("question" to "工具自测？"),
        "note" to jsonObjectOf("text" to "工具自测笔记（N198）"),
        "history_recall" to jsonObjectOf("query" to "自测"),
        "notify_user" to jsonObjectOf("title" to "工具自测", "text" to "工具自测通知（N203）"),
    )

/** 简便构造：字符串/数字值的 JSON 对象。 */
private fun jsonObjectOf(vararg pairs: Pair<String, Any>): JsonObject =
    JsonObject(
        pairs.associate { (key, value) ->
            key to
                when (value) {
                    is Number -> kotlinx.serialization.json.JsonPrimitive(value)
                    else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
                }
        },
    )

/** 文本直通 JSON 元素（结构复杂的 fixture 直接写 JSON 字符串）。 */
private fun jsonElementOf(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
