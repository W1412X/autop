/**
 * 图片保留规则 `history.image_retention`（docs/tech/04 §3.4）：
 * Image 块从新到旧保留 `keep` 个，其余替换为 stub。
 */
package com.example.autop.agent.runtime.rules

import com.example.autop.agent.HistoryRenderRule
import com.example.autop.agent.RenderItem
import com.example.autop.agent.USER_IMAGE_NAME_PREFIX
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block

/** 图片保留。 */
internal class ImageRetentionRule(
    private val prompts: PromptRepository,
) : HistoryRenderRule {
    override val id: String = ID

    override fun apply(
        items: List<RenderItem>,
        config: ConfigSnapshot,
    ): List<RenderItem> {
        var keep = config[AgentConfig.HISTORY].image.keep
        // 从新到旧处理，保留最新的 keep 张图。
        return items
            .asReversed()
            .map { item ->
                val stub by lazy(LazyThreadSafetyMode.NONE) { stubFor(item) }
                item.copy(
                    msg =
                        item.msg.mapBlocks { block ->
                            when {
                                block !is Block.Image -> listOf(block)
                                isUserPicked(block) -> listOf(block)
                                keep > 0 -> {
                                    keep--
                                    listOf(block)
                                }
                                else -> listOfNotNull(stub)
                            }
                        },
                )
            }.asReversed()
    }

    /** 用户主动发送的图片是对话内容，不参与「只留最近 N 张」的截图淘汰。 */
    private fun isUserPicked(image: Block.Image): Boolean =
        image.fileRef.substringAfterLast('/').startsWith(USER_IMAGE_NAME_PREFIX)

    private fun stubFor(item: RenderItem): Block.Text? =
        prompts
            .render(ID_STUB, mapOf("seq" to item.seq.toString()))
            .getOrNull()
            ?.text
            ?.let { Block.Text(it) }

    private companion object {
        const val ID = "history.image_retention"
        const val ID_STUB = "agent.history.image_stub"
    }
}
