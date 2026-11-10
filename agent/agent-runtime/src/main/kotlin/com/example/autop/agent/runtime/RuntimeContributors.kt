/**
 * runtime 内置的上下文贡献者（docs/tech/04 §3.10）：
 * context.role / context.safety / context.tool_guide（SYSTEM_STATIC）、
 * context.run_mode（SYSTEM_DYNAMIC）、context.trigger / context.goal（FIRST_USER）。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.ContextContributor
import com.example.autop.agent.ContextPart
import com.example.autop.agent.ContextRequest
import com.example.autop.agent.Placement
import com.example.autop.agent.runtime.rules.ImageRetentionRule
import com.example.autop.agent.runtime.rules.LongTextTruncateRule
import com.example.autop.agent.runtime.rules.ObservationDegradeRule
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.UiConfig
import com.example.autop.core.prompt.PromptRepository
import java.util.Locale

/** 模板贡献基类：渲染固定模板，记录版本。 */
internal abstract class TemplateContributor(
    private val prompts: PromptRepository,
) : ContextContributor {
    /** 模板 id；需要按请求选择模板时覆写 [templateIdFor]。 */
    protected open val templateId: String? = null

    protected abstract val placement: Placement

    protected open fun templateIdFor(req: ContextRequest): String = templateId.orEmpty()

    override suspend fun contribute(req: ContextRequest): ContextPart? {
        val rendered = prompts.render(templateIdFor(req), vars(req)).getOrNull() ?: return null
        return ContextPart(
            placement = placement,
            text = rendered.text,
            promptIds = mapOf(rendered.id to rendered.version),
        )
    }

    protected open fun vars(req: ContextRequest): Map<String, String> = emptyMap()
}

/** `context.role`：助手角色。 */
internal class RoleContributor(
    prompts: PromptRepository,
) : TemplateContributor(prompts) {
    override val id: String = "context.role"
    override val templateId: String = "agent.system.role"
    override val placement: Placement = Placement.SYSTEM_STATIC
}

/** `context.safety`：安全规则。 */
internal class SafetyContributor(
    prompts: PromptRepository,
) : TemplateContributor(prompts) {
    override val id: String = "context.safety"
    override val templateId: String = "agent.system.safety"
    override val placement: Placement = Placement.SYSTEM_STATIC
}

/** `context.tool_guide`：工具使用要点。 */
internal class ToolGuideContributor(
    prompts: PromptRepository,
) : TemplateContributor(prompts) {
    override val id: String = "context.tool_guide"
    override val templateId: String = "agent.system.tool_guide"
    override val placement: Placement = Placement.SYSTEM_STATIC

    /** 省略/截断说明随配置渲染（WP3.9）：规则未启用或参数关闭时不输出对应段落。 */
    override fun vars(req: ContextRequest): Map<String, String> {
        val history = req.config[AgentConfig.HISTORY]
        val rules = history.rules
        return mapOf(
            "observationOmitted" to flag(history.observation.keepFull > 0 && ObservationDegradeRule.ID in rules),
            "textTruncated" to flag(history.truncate.maxChars > 0 && LongTextTruncateRule.ID in rules),
            "imagesOmitted" to flag(history.image.keep >= 0 && ImageRetentionRule.ID in rules),
        )
    }

    private fun flag(enabled: Boolean): String = if (enabled) "1" else ""
}

/** `context.output`：输出要求（语言 + 简洁；语言取 `ui.appearance.language`，system 跟随系统）。 */
internal class OutputContributor(
    prompts: PromptRepository,
) : TemplateContributor(prompts) {
    override val id: String = "context.output"
    override val templateId: String = "agent.system.output"
    override val placement: Placement = Placement.SYSTEM_STATIC

    override fun vars(req: ContextRequest): Map<String, String> = mapOf("lang" to languageLabel(req))

    private fun languageLabel(req: ContextRequest): String =
        when (req.config[UiConfig.APPEARANCE].language) {
            LANG_EN -> "English"
            LANG_ZH -> "中文"
            else -> if (Locale.getDefault().language == LANG_EN) "English" else "中文"
        }

    private companion object {
        const val LANG_EN: String = "en"
        const val LANG_ZH: String = "zh"
    }
}

/** `context.run_mode`：前台对话 / 后台任务（`vars["runMode"]`）。 */
internal class RunModeContributor(
    prompts: PromptRepository,
) : TemplateContributor(prompts) {
    override val id: String = "context.run_mode"
    override val placement: Placement = Placement.SYSTEM_DYNAMIC

    override fun templateIdFor(req: ContextRequest): String {
        val mode = if (req.vars[RUN_MODE] == MODE_BACKGROUND) MODE_BACKGROUND else MODE_FOREGROUND
        return "agent.system.run_mode.$mode"
    }

    private companion object {
        const val RUN_MODE = "runMode"
        const val MODE_BACKGROUND = "background"
        const val MODE_FOREGROUND = "foreground"
    }
}

/** `context.trigger`：触发事件（`vars` 中 `trigger.` 前缀的键，不可信数据）。 */
internal class TriggerContributor(
    prompts: PromptRepository,
) : TemplateContributor(prompts) {
    override val id: String = "context.trigger"
    override val templateId: String = "agent.context.trigger"
    override val placement: Placement = Placement.FIRST_USER

    override fun vars(req: ContextRequest): Map<String, String> {
        val trigger = req.vars.filterKeys { it.startsWith(PREFIX) }
        val source = trigger["${PREFIX}source"].orEmpty()
        val details =
            trigger
                .filterKeys { it != "${PREFIX}source" }
                .entries
                .joinToString("\n") { (key, value) -> "${key.removePrefix(PREFIX)}: $value" }
        return mapOf("source" to source, "details" to details)
    }

    override suspend fun contribute(req: ContextRequest): ContextPart? {
        if (req.vars.none { it.key.startsWith(PREFIX) }) return null
        return super.contribute(req)
    }

    private companion object {
        const val PREFIX = "trigger."
    }
}

/** `context.goal`：任务目标。 */
internal class GoalContributor(
    prompts: PromptRepository,
) : TemplateContributor(prompts) {
    override val id: String = "context.goal"
    override val templateId: String = "agent.context.goal"
    override val placement: Placement = Placement.FIRST_USER

    override fun vars(req: ContextRequest): Map<String, String> = mapOf("goal" to (req.goal ?: ""))

    override suspend fun contribute(req: ContextRequest): ContextPart? {
        if (req.goal.isNullOrBlank()) return null
        return super.contribute(req)
    }
}
