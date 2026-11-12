/**
 * [ExperienceExtractor] 实现（docs/design/agent-design.md §8）：开关
 * `features.experienceExtraction`（默认关）开启时，用轻量模型（LIGHT，未配置回退
 * MAIN）渲染 `memory.experience.extract`；输出 `{"append":"…"}` 时把增量追加到
 * `/apps/{package}/_manual`（标题用应用名，docs/tech/06 §4）。任何失败只记日志，
 * 不影响 Run 收口。
 */
package com.example.autop.memory.impl

import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FeaturesConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.AppLauncher
import com.example.autop.llm.Block
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.complete
import com.example.autop.memory.ExperienceExtractor
import com.example.autop.memory.ExperienceRequest
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** App 手册经验提炼器。 */
@Suppress("LongParameterList") // 提炼链路的正交依赖（开关/模板/模型/应用名/写入/日志）
public class ExperienceExtractorImpl(
    private val store: MemoryStore,
    private val gateway: LlmGateway,
    private val prompts: PromptRepository,
    private val appLauncher: AppLauncher,
    private val configProvider: ConfigProvider,
    private val logger: Logger,
    private val json: Json = AutopJson.default,
) : ExperienceExtractor {
    @Suppress("TooGenericExceptionCaught") // 可选增强：任何失败降级为不写入
    override suspend fun extract(request: ExperienceRequest): Boolean {
        if (!configProvider.current()[FeaturesConfig.FEATURES].experienceExtraction) return false
        return try {
            extractManual(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(
                TAG,
                "experience_extract_failed",
                "runId" to request.runId.value,
                "error" to (e.message ?: "unknown"),
            )
            false
        }
    }

    @Suppress("ReturnCount") // 逐级早退（渲染/补全/解析/空输出）是提炼链的自然结构
    private suspend fun extractManual(request: ExperienceRequest): Boolean {
        val path =
            MemoryPath.ROOT
                .child("apps")
                .child(request.packageName)
                .child(MANUAL_NAME)
        val manual = store.get(path)?.content.orEmpty()
        val appName = appNameOf(request.packageName)
        val rendered =
            prompts.render(
                ID_EXTRACT,
                mapOf(VAR_APP to appName, VAR_STEPS to request.steps, VAR_MANUAL to manual),
            )
        val userText =
            when (rendered) {
                is Outcome.Ok -> rendered.value.text
                is Outcome.Err -> {
                    logger.w(TAG, "experience_render_failed", "runId" to request.runId.value)
                    return false
                }
            }
        val text =
            when (val completed = completeText(userText)) {
                is Outcome.Ok -> completed.value
                is Outcome.Err -> {
                    logger.w(TAG, "experience_llm_failed", "runId" to request.runId.value)
                    return false
                }
            }
        val dto = parse(text)
        if (dto == null) {
            logger.w(
                TAG,
                "experience_output_unparsable",
                "runId" to request.runId.value,
                "text" to text.take(LOG_TEXT_CHARS),
            )
            return false
        }
        val append = dto.append
        if (append.isNullOrBlank()) return false
        val written =
            store.write(
                MemoryWrite(
                    path = path,
                    mode = WriteMode.APPEND,
                    title = appName,
                    content = append,
                    source = MemorySource.AGENT,
                    runId = request.runId,
                    reason = REASON,
                ),
            )
        if (written is Outcome.Ok) {
            logger.i(TAG, "experience_written", "runId" to request.runId.value, "path" to path.value)
        }
        return written is Outcome.Ok
    }

    private suspend fun appNameOf(packageName: String): String =
        runCatching { appLauncher.listApps(null).firstOrNull { it.packageName == packageName }?.label }
            .getOrNull() ?: packageName

    /** 单轮轻量补全（LIGHT 优先、MAIN 兜底），返回拼接文本。 */
    private suspend fun completeText(userText: String): Outcome<String> {
        val llm =
            when (val got = gateway.forDefault()) {
                is Outcome.Ok -> got.value
                is Outcome.Err -> return Outcome.Err(got.error)
            }
        val request =
            LlmRequest(
                system = emptyList(),
                messages = listOf(Msg(Role.USER, listOf(Block.Text(userText)))),
                tools = emptyList(),
                toolChoice = ToolChoice.NONE,
                maxOutputTokens = MAX_OUTPUT_TOKENS,
            )
        return when (val completed = llm.complete(request)) {
            is Outcome.Ok ->
                Outcome.Ok(
                    completed.value.message.blocks
                        .filterIsInstance<Block.Text>()
                        .joinToString("") { it.text },
                )
            is Outcome.Err -> Outcome.Err(completed.error)
        }
    }

    /** `memory.experience.extract` 输出 `{"append":"…"}` / `{"none":true}`（可能带围栏）。 */
    private fun parse(text: String): ExtractDto? {
        val body = extractJsonObject(text) ?: return null
        return try {
            json.decodeFromString(ExtractDto.serializer(), body)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 提炼输出结构。 */
    @Serializable
    private data class ExtractDto(
        val append: String? = null,
        val none: Boolean? = null,
    )

    private companion object {
        const val TAG = "ExperienceExtractor"
        const val ID_EXTRACT = "memory.experience.extract"
        const val VAR_APP = "appName"
        const val VAR_STEPS = "steps"
        const val VAR_MANUAL = "manual"
        const val MANUAL_NAME = "_manual"
        const val REASON = "experience_extraction"

        /** 轻量补全的最大输出 tokens。 */
        const val MAX_OUTPUT_TOKENS = 1024

        /** 解析失败日志携带的原文截断长度。 */
        const val LOG_TEXT_CHARS = 120
    }
}

/**
 * 从模型输出中提取 JSON 对象文本：取首 `{` 到末 `}`（任务草稿侧有同名工具，
 * 但模块依赖不允许跨域引用，故在此私有实现）。
 */
private fun extractJsonObject(text: String): String? {
    val start = text.indexOf(FIRST_CHAR)
    val end = text.lastIndexOf(LAST_CHAR)
    if (start < 0 || end <= start) return null
    return text.substring(start, end + 1)
}

private const val FIRST_CHAR = '{'
private const val LAST_CHAR = '}'
