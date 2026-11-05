/**
 * 调试页 ViewModel（docs/tech/08 §4.6 me/debug）：
 * 采集走 `tool.observe`（统一 JSON + 统计），截图走 `tool.screenshot`（普通截图），
 * 导出走 [DebugRuntime.exportSnapshot]（快照夹具，可选脱敏），工具试执行走 [DebugToolExecutor]。
 */
package com.example.autop.feature.settings.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.TextTokenEstimator
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.SnapshotExport
import com.example.autop.llm.Block
import com.example.autop.llm.LlmRequestLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

@HiltViewModel
internal class DebugViewModel
    @Inject
    constructor(
        private val runtime: DebugRuntime,
        private val configProvider: ConfigProvider,
        private val screenReader: ScreenReader,
        private val executor: DebugToolExecutor,
        private val dispatchers: DispatcherProvider,
        private val requestLog: LlmRequestLog,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(DebugUiState(toolIds = executor.toolOptions().map { it.id }))

        val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

        fun onAction(action: DebugAction) {
            when (action) {
                DebugAction.Capture -> capture()
                DebugAction.Screenshot -> screenshot()
                is DebugAction.SetObserveMode -> _uiState.update { it.copy(observeMode = action.mode) }
                DebugAction.Export -> export()
                is DebugAction.SetRedact -> _uiState.update { it.copy(redact = action.value) }
                is DebugAction.SelectTool -> _uiState.update { it.copy(selectedToolId = action.toolId) }
                is DebugAction.SetArgs -> _uiState.update { it.copy(argsText = action.text) }
                DebugAction.ExecuteTool -> executeTool()
                DebugAction.ShowLastRequest ->
                    _uiState.update { it.copy(showLastRequest = true, lastRequest = requestView()) }
                DebugAction.HideLastRequest -> _uiState.update { it.copy(showLastRequest = false) }
                DebugAction.DismissError -> _uiState.update { it.copy(error = null) }
            }
        }

        /** 采集当前屏幕（3 秒后，给用户时间切到目标应用）。 */
        private fun capture() {
            if (_uiState.value.countdown != null) return
            viewModelScope.launch(dispatchers.main) {
                for (seconds in COUNTDOWN_SECONDS downTo 1) {
                    _uiState.update { it.copy(countdown = seconds) }
                    delay(COUNTDOWN_TICK_MS)
                }
                _uiState.update { it.copy(countdown = null, busy = true) }
                val started = System.currentTimeMillis()
                val output =
                    executor.execute(
                        TOOL_OBSERVE,
                        buildJsonObject {
                            put("mode", _uiState.value.observeMode)
                            put("full", true)
                        },
                        runtime.context(configProvider.current()),
                    )
                applyObservation(output, System.currentTimeMillis() - started)
                _uiState.update { it.copy(busy = false) }
            }
        }

        private fun applyObservation(
            output: ToolOutput,
            elapsedMs: Long,
        ) {
            if (!output.ok) {
                _uiState.update { it.copy(error = DebugError.CaptureFailed) }
                return
            }
            val text = textOf(output)
            val tokens =
                configProvider.current()[AgentConfig.TOKENS].let {
                    TextTokenEstimator.estimate(text, it.cjkFactor, it.charsPerToken)
                }
            _uiState.update {
                it.copy(
                    serializedText = text,
                    stats =
                        DebugStats(
                            nodeCount = output.observation?.nodeCount ?: 0,
                            interactiveCount =
                                output.data
                                    ?.jsonObject
                                    ?.get(KEY_INTERACTIVE)
                                    ?.jsonPrimitive
                                    ?.intOrNull ?: 0,
                            tokens = tokens,
                            elapsedMs = elapsedMs,
                        ),
                )
            }
        }

        /** 截图（`tool.screenshot` 普通截图，不画叠加）。 */
        private fun screenshot() {
            viewModelScope.launch(dispatchers.main) {
                _uiState.update { it.copy(busy = true) }
                val output = executor.execute(TOOL_SCREENSHOT, SHOT_ARGS, runtime.context(configProvider.current()))
                val image = output.content.filterIsInstance<Block.Image>().firstOrNull()
                _uiState.update {
                    it.copy(
                        busy = false,
                        screenshotPath = image?.fileRef ?: it.screenshotPath,
                        error = if (image == null) DebugError.CaptureFailed else null,
                    )
                }
            }
        }

        /** 导出快照夹具到 `Download/autop/`（docs/tech/05 §11）。 */
        private fun export() {
            viewModelScope.launch(dispatchers.io) {
                _uiState.update { it.copy(busy = true) }
                when (val got = screenReader.snapshot()) {
                    is Outcome.Err -> _uiState.update { it.copy(busy = false, error = DebugError.ExportFailed) }
                    is Outcome.Ok -> exportOf(got.value)
                }
            }
        }

        private fun exportOf(snapshot: ScreenSnapshot) {
            val text =
                runCatching {
                    val chosen = if (_uiState.value.redact) redactSnapshot(snapshot) else snapshot
                    AutopJson.default.encodeToString(SnapshotExport.serializer(), SnapshotExport(snapshot = chosen))
                }.getOrNull()
            val name = "snapshot-${snapshot.capturedAt}.json"
            val saved = text?.let { runCatching { runtime.exportSnapshot(name, it) }.getOrNull() }
            _uiState.update {
                it.copy(
                    busy = false,
                    exportedFile = saved ?: it.exportedFile,
                    error = if (saved == null) DebugError.ExportFailed else null,
                )
            }
        }

        /** 工具试执行（docs/tech/08 §4.6）。 */
        private fun executeTool() {
            val toolId = _uiState.value.selectedToolId
            if (toolId == null) {
                _uiState.update { it.copy(error = DebugError.ToolMissing) }
                return
            }
            val args = parseArgs(_uiState.value.argsText)
            if (args == null) {
                _uiState.update { it.copy(error = DebugError.ArgsInvalid) }
                return
            }
            viewModelScope.launch(dispatchers.main) {
                _uiState.update { it.copy(busy = true) }
                val output = executor.execute(toolId, args, runtime.context(configProvider.current()))
                _uiState.update { it.copy(busy = false, toolOutcome = outcomeOf(toolId, output)) }
            }
        }

        /**
         * 最近一次采样请求 → 展示视图（system 估算 token 供核对 L0 ≤ 1500，docs/tech/10 WP8；
         * WP10 扩展 live 视图与 token 分布：逐条消息摘要 + system/消息/工具/合计估算）。
         */
        private fun requestView(): DebugRequestView? {
            val snapshot = requestLog.last() ?: return null
            val tokens = configProvider.current()[AgentConfig.TOKENS]

            fun estimate(text: String): Int = TextTokenEstimator.estimate(text, tokens.cjkFactor, tokens.charsPerToken)
            val systemText = snapshot.request.system.joinToString("\n\n") { it.text }
            val messageText =
                snapshot.request.messages.joinToString("\n") { msg ->
                    msg.blocks.joinToString("\n") { blockText(it) }
                }
            val toolText = snapshot.request.tools.joinToString("\n") { "${it.name}: ${it.description}" }
            val systemTokens = estimate(systemText)
            val messageTokens = estimate(messageText)
            val toolTokens = estimate(toolText)
            return DebugRequestView(
                modelId = snapshot.modelId,
                capturedAt = snapshot.capturedAtMs,
                systemText = systemText,
                systemTokens = systemTokens,
                messageCount = snapshot.request.messages.size,
                toolCount = snapshot.request.tools.size,
                inputTokens = snapshot.usage?.inputTokens,
                outputTokens = snapshot.usage?.outputTokens,
                liveMessages =
                    snapshot.request.messages.map { msg ->
                        DebugLiveMessage(
                            role = msg.role,
                            text = msg.blocks.joinToString("\n") { blockSummary(it) }.truncate(LIVE_TEXT_LIMIT),
                        )
                    },
                tokenParts =
                    listOf(
                        DebugTokenPart(DebugTokenSection.SYSTEM, systemTokens),
                        DebugTokenPart(DebugTokenSection.MESSAGES, messageTokens),
                        DebugTokenPart(DebugTokenSection.TOOLS, toolTokens),
                        DebugTokenPart(DebugTokenSection.TOTAL, systemTokens + messageTokens + toolTokens),
                    ),
            )
        }

        /** 消息块 → live 视图摘要（图片/思考给占位，工具结果取文本，WP10）。 */
        private fun blockSummary(block: Block): String =
            when (block) {
                is Block.Text -> block.text
                is Block.Image -> "[图片 ${block.width}x${block.height}]"
                is Block.ToolCall -> "-> ${block.name}(${block.argsJson})"
                is Block.ToolResult -> block.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
                is Block.Thinking -> "[思考]"
            }

        /** 消息块 → token 估算文本（图片取 alt，其余取正文，WP10）。 */
        private fun blockText(block: Block): String =
            when (block) {
                is Block.Text -> block.text
                is Block.Image -> block.alt
                is Block.ToolCall -> block.name + block.argsJson
                is Block.ToolResult -> block.content.joinToString("\n") { blockText(it) }
                is Block.Thinking -> block.text
            }

        private companion object {
            const val COUNTDOWN_SECONDS: Int = 3
            const val COUNTDOWN_TICK_MS: Long = 1000
            const val TOOL_OBSERVE: String = "tool.observe"
            const val TOOL_SCREENSHOT: String = "tool.screenshot"
            const val KEY_INTERACTIVE: String = "interactiveCount"
            const val LIVE_TEXT_LIMIT: Int = 200
            val SHOT_ARGS: JsonObject = JsonObject(emptyMap())
        }
    }

/** 截断到 [limit] 字符（超出加省略号），防 live 视图爆屏。 */
private fun String.truncate(limit: Int): String = if (length <= limit) this else take(limit) + "…"
