package com.example.autop.agent.runtime

import com.example.autop.agent.RenderItem
import com.example.autop.agent.USER_IMAGE_NAME_PREFIX
import com.example.autop.agent.runtime.rules.ImageRetentionRule
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageRetentionRuleTest {
    private val rule = ImageRetentionRule(TestPromptRepository())

    @Test
    fun `user picked images are kept and do not use up the screenshot quota`() {
        val items =
            listOf(
                item(1, image("threads/t/img/${USER_IMAGE_NAME_PREFIX}1-0.jpg")),
                item(2, image("threads/t/img/screen-1.jpg")),
                item(3, image("threads/t/img/screen-2.jpg")),
            )

        val result = rule.apply(items, FakeConfigProvider().current())

        val kept = result.flatMap { it.msg.blocks.filterIsInstance<Block.Image>() }.map { it.fileRef }
        // 默认 keep=1：只留最新一张截图，用户图片不受影响。
        assertThat(kept)
            .containsExactly("threads/t/img/${USER_IMAGE_NAME_PREFIX}1-0.jpg", "threads/t/img/screen-2.jpg")
            .inOrder()
    }

    private fun item(
        seq: Long,
        image: Block.Image,
    ): RenderItem = RenderItem(seq = seq, msg = Msg(Role.USER, listOf(image)), observation = null, toolId = null)

    private fun image(fileRef: String): Block.Image =
        Block.Image(fileRef = fileRef, mime = "image/jpeg", width = 4, height = 4)
}
