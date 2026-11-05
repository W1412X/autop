package com.example.autop.llm.anthropic

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.core.prompt.PromptSource
import com.example.autop.core.prompt.PromptTemplate
import com.example.autop.core.prompt.RenderedPrompt
import com.example.autop.core.prompt.renderPrompt
import com.example.autop.llm.ImageLoader
import com.example.autop.llm.LoadedImage

/** 固定字节的图片假件（记录请求参数）；[broken] 模拟读取失败返回 null（N178 降级用例）。 */
internal class FakeImageLoader(
    private val bytes: ByteArray = byteArrayOf(1, 2, 3),
    private val broken: Boolean = false,
) : ImageLoader {
    val requests: MutableList<Triple<String, String, Int>> = mutableListOf()

    override suspend fun load(
        fileRef: String,
        mime: String,
        maxEdge: Int,
    ): LoadedImage? {
        requests += Triple(fileRef, mime, maxEdge)
        return if (broken) null else LoadedImage(bytes, mime)
    }
}

/** 只认识 leading_user 模板的假件（真实渲染逻辑复用 renderPrompt）。 */
internal class FakePrompts : PromptRepository {
    private val template =
        PromptTemplate(
            id = LEADING_USER_ID,
            version = 1,
            vars = emptySet(),
            optionalVars = emptySet(),
            meta = emptyMap(),
            body = "Please respond.",
            source = PromptSource.BUILTIN,
        )

    override fun get(id: String): PromptTemplate = template

    override fun render(
        id: String,
        vars: Map<String, String>,
    ): Outcome<RenderedPrompt> = renderPrompt(template, vars)

    override fun all(): List<PromptTemplate> = listOf(template)
}
