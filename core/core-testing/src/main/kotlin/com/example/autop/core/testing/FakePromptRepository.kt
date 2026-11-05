package com.example.autop.core.testing

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.core.prompt.PromptSource
import com.example.autop.core.prompt.PromptTemplate
import com.example.autop.core.prompt.RenderedPrompt
import com.example.autop.core.prompt.parsePromptTemplate
import com.example.autop.core.prompt.renderPrompt
import java.io.File

/**
 * 测试用 [PromptRepository]（docs/tech/02 §5）：从 Gradle 系统属性 `autop.promptDir`
 * 指向的真实模板目录（`core-prompt-impl/src/main/assets/prompts`）加载全部内置模板，
 * id 重复或模板非法直接失败（与内置加载器同策略）。
 */
public class FakePromptRepository(
    promptDir: File = File(promptDirPath()),
) : PromptRepository {
    private val templates: Map<String, PromptTemplate> = loadTemplates(promptDir)

    override fun get(id: String): PromptTemplate = templates[id] ?: error("unknown prompt id '$id'")

    override fun render(
        id: String,
        vars: Map<String, String>,
    ): Outcome<RenderedPrompt> = renderPrompt(get(id), vars)

    /** 全部模板，按 id 排序（接口语义与 PromptRepositoryImpl 一致）。 */
    override fun all(): List<PromptTemplate> = templates.values.sortedBy { it.id }
}

private const val PROMPT_DIR_PROPERTY = "autop.promptDir"

private const val TEMPLATE_EXTENSION = "md"

private fun promptDirPath(): String = System.getProperty(PROMPT_DIR_PROPERTY) ?: error("$PROMPT_DIR_PROPERTY not set")

private fun loadTemplates(dir: File): Map<String, PromptTemplate> {
    val files = dir.walkTopDown().filter { it.isFile && it.extension == TEMPLATE_EXTENSION }.sortedBy { it.path }
    val templates = LinkedHashMap<String, PromptTemplate>()
    for (file in files) {
        val template = parsePromptTemplate(file.readText(), PromptSource.BUILTIN)
        check(template.id !in templates) { "duplicate prompt id '${template.id}'" }
        templates[template.id] = template
    }
    check(templates.isNotEmpty()) { "no prompt templates found in $dir" }
    return templates
}
