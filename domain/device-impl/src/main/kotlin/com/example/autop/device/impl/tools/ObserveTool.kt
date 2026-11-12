/**
 * `tool.observe`（agent-harness-overhaul WP1.7）：mode 缺省时按优先级自动选择，
 * 显式 mode 不自动更换；始终返回完整结果（tool_optimize，删 `full` 与差量）。
 * 调试图仅在最终用了 visual + 调试开关时附上。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.ObserveMode
import com.example.autop.device.impl.ObserveModeResolver
import com.example.autop.device.impl.ObserveSelection
import com.example.autop.device.impl.observation.ScreenObserver
import com.example.autop.device.impl.vision.VisualDebugCapture
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** 屏幕观测工具。 */
@Singleton
public class ObserveTool
    @Inject
    constructor(
        private val observer: ScreenObserver,
        private val debug: VisualDebugCapture,
        private val modes: ObserveModeResolver,
        private val prompts: PromptRepository,
    ) : Tool {
        override val id: String = "tool.observe"

        override val spec = DeviceToolSpecs.observe

        override val access: ToolAccess = ToolAccess.READ_SCREEN

        /** §2.3：mode 枚举与说明按当前生效模式动态生成；不参与自动选择的方式在说明里标注。 */
        override fun specFor(ctx: ToolContext): ToolSpec {
            val config = ctx.config[DeviceConfig.OBSERVE]
            val effective = modes.effective(config)
            // 无生效模式（服务/模型未就绪）时枚举回退配置清单，但明确告知可能不可用，避免模型误以为一切正常。
            val ordered = effective.ifEmpty { config.priority.mapNotNull(ObserveMode::parse) }
            val warn = if (effective.isEmpty()) render(PROMPT_NO_EFFECTIVE_MODE) + "\n" else ""
            val notAuto = render(PROMPT_MODE_NOT_AUTO)
            val modeList =
                ordered
                    .mapIndexed { index, mode ->
                        val text =
                            prompts
                                .render("$MODE_PROMPT_PREFIX${mode.id}")
                                .getOrNull()
                                ?.text
                                .orEmpty()
                        val suffix = if (config.modes.of(mode.id).auto) "" else " $notAuto"
                        "${index + 1}. $text$suffix"
                    }.joinToString("\n")
            return DeviceToolSpecs.observeFor(
                modeIds = ordered.map { it.id },
                modeList = warn + modeList,
            )
        }

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val rawMode = args.str("mode")
            val selection =
                if (rawMode == null) {
                    ObserveSelection.Auto
                } else {
                    val parsed = ObserveMode.parse(rawMode) ?: return invalidArgOutput(ctx, rawMode)
                    ObserveSelection.Explicit(parsed)
                }
            val captureDebug = ctx.debugImages && mayUseVisual(selection)
            if (captureDebug) debug.begin()
            val got = observer.observe(ctx, selection)
            val taken = if (captureDebug) debug.take() else emptyList()
            val observed =
                when (got) {
                    is Outcome.Ok -> got.value
                    is Outcome.Err -> return errorOutput(got.error)
                }
            val debugImages =
                if (observed.screen.model.mode == ObserveMode.VISUAL) {
                    taken.map { Block.Image(it.fileRef, "image/png", it.width, it.height, it.alt) }
                } else {
                    emptyList()
                }
            val blocks =
                listOf<Block>(Block.Text(observed.observation.text)) +
                    debugImages
            return ToolOutput(
                ok = true,
                content = blocks,
                data = buildJsonObject { put("interactiveCount", observed.observation.interactiveCount) },
                observation = observed.ref,
            )
        }

        private fun invalidArgOutput(
            ctx: ToolContext,
            rawMode: String,
        ): ToolOutput {
            val allowed =
                modes
                    .effective(ctx.config[DeviceConfig.OBSERVE])
                    .map { it.id }
                    .ifEmpty { ObserveMode.entries.map { it.id } }
                    .joinToString("、")
            return toolError(
                CommonErrorCodes.INVALID_ARGUMENT,
                render(PROMPT_INVALID_MODE, mapOf("got" to rawMode, "allowed" to allowed)),
            )
        }

        /** 自动选择可能落到 visual；显式只有 visual 会用到调试图。 */
        private fun mayUseVisual(selection: ObserveSelection): Boolean =
            when (selection) {
                ObserveSelection.Auto -> true
                is ObserveSelection.Explicit -> selection.mode == ObserveMode.VISUAL
            }

        private fun render(
            id: String,
            vars: Map<String, String> = emptyMap(),
        ): String =
            prompts
                .render(id, vars)
                .getOrNull()
                ?.text
                .orEmpty()

        private companion object {
            const val MODE_PROMPT_PREFIX = "tool.observe.mode."
            const val PROMPT_NO_EFFECTIVE_MODE = "tool.observe.no_effective_mode"
            const val PROMPT_MODE_NOT_AUTO = "tool.observe.mode_not_auto"
            const val PROMPT_INVALID_MODE = "tool.observe.invalid_mode"
        }
    }
