/**
 * `tool.observe`（docs/design/observation-modes-coordinate-actions §2、§3）：
 * 按显式 mode 获取统一 JSON 观测；a11y/visual 的 `full=false` 出差量，llm 恒完整。
 * 不再自动附图、不再自动切换模式；调试图仅在 visual + 调试开关下采集。
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

        /** §2.3：mode 枚举与说明按当前生效模式动态生成。 */
        override fun specFor(ctx: ToolContext): ToolSpec {
            val config = ctx.config[DeviceConfig.OBSERVE]
            val effective = modes.effective(config)
            // 无生效模式（服务/模型未就绪）时枚举回退配置清单，但明确告知可能不可用，避免模型误以为一切正常。
            val ordered = effective.ifEmpty { config.priority.mapNotNull(ObserveMode::parse) }
            val warn = if (effective.isEmpty()) "$NO_EFFECTIVE_MODE\n" else ""
            return DeviceToolSpecs.observeFor(
                modeIds = ordered.map { it.id },
                modeList =
                    warn +
                        ordered
                            .mapIndexed { index, mode ->
                                val text =
                                    prompts
                                        .render("$MODE_PROMPT_PREFIX${mode.id}")
                                        .getOrNull()
                                        ?.text
                                        .orEmpty()
                                if (index == 0) "（优先）$text" else text
                            }.joinToString("\n"),
            )
        }

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val mode = ObserveMode.parse(args.str("mode")) ?: return invalidArgOutput(args)
            val full = args.bool("full") == true
            val region =
                when (val parsed = regionFor(mode, args)) {
                    is RegionArg.Malformed -> return toolError(CommonErrorCodes.INVALID_ARGUMENT, MALFORMED_REGION)
                    is RegionArg.Value -> parsed.rect
                    is RegionArg.None -> null
                }
            val captureDebug = mode == ObserveMode.VISUAL && ctx.debugImages
            if (captureDebug) debug.begin()
            val got = observer.observe(ctx, mode, full, region)
            val debugImages = if (captureDebug) debug.take() else emptyList()
            val observed =
                when (got) {
                    is Outcome.Ok -> got.value
                    is Outcome.Err -> return errorOutput(got.error)
                }
            val notes = observationNotes(mode, full, args)
            val blocks =
                listOf<Block>(Block.Text(observed.observation.text)) +
                    notes.map { Block.Text(it) } +
                    debugImages.map { Block.Image(it.fileRef, "image/png", it.width, it.height, it.alt) }
            return ToolOutput(
                ok = true,
                content = blocks,
                data = buildJsonObject { put("interactiveCount", observed.observation.interactiveCount) },
                observation = observed.ref,
            )
        }

        private fun invalidArgOutput(args: JsonObject): ToolOutput =
            toolError(
                CommonErrorCodes.INVALID_ARGUMENT,
                "mode 必须是 a11y、visual、llm 之一（收到 ${args.str("mode").orEmpty()}）",
            )

        /** region 只对 llm 生效；其余模式一律忽略（不解析，避免误报格式错误）。 */
        private fun regionFor(
            mode: ObserveMode,
            args: JsonObject,
        ): RegionArg = if (mode == ObserveMode.LLM) regionOf(args.obj("region")) else RegionArg.None

        private companion object {
            const val MALFORMED_REGION = "region 必须是 [x1,y1,x2,y2]（0–1000，x1<x2、y1<y2）"
            const val MODE_PROMPT_PREFIX = "tool.observe.mode."
            const val NO_EFFECTIVE_MODE = "（当前无可用观测模式：服务/模型未就绪，所选模式可能返回 device.mode_unavailable）"
        }
    }

/**
 * 静默忽略参数会让模型误以为生效；这里把语义差异说清楚（观察模式设计文档 §5.9）。
 * `region` 只对 llm 生效；llm 恒为完整结果，`full` 无意义。
 */
internal fun observationNotes(
    mode: ObserveMode,
    full: Boolean,
    args: JsonObject,
): List<String> =
    buildList {
        if (mode != ObserveMode.LLM && args.containsKey("region")) add(REGION_IGNORED)
        if (mode == ObserveMode.LLM && full) add(FULL_IGNORED)
    }

internal const val REGION_IGNORED = "region 只对 mode=llm 生效，本次已忽略；a11y/visual 观测始终是整屏。"
internal const val FULL_IGNORED = "llm 观测固定返回完整结果，full 已忽略。"
