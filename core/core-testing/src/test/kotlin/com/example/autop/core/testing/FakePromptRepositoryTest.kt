package com.example.autop.core.testing

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptSource
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** 用真实模板目录（autop.promptDir）验证加载与查询。 */
class FakePromptRepositoryTest {
    @Test
    fun `loads all builtin templates from the real prompt dir`() {
        val repository = FakePromptRepository()
        assertThat(repository.all()).hasSize(221)
    }

    @Test
    fun `known template ids resolve as builtin`() {
        val repository = FakePromptRepository()
        assertThat(repository.get("tool.get_device_state").source).isEqualTo(PromptSource.BUILTIN)
        assertThat(repository.get("agent.compaction.summary_wrapper").id).isEqualTo("agent.compaction.summary_wrapper")
    }

    @Test
    fun `unknown id is a failure`() {
        val repository = FakePromptRepository()
        val e = assertThrows(IllegalStateException::class.java) { repository.get("ghost.id") }
        assertThat(e).hasMessageThat().contains("unknown prompt id")
    }

    @Test
    fun `render delegates to the real renderer`() {
        val repository = FakePromptRepository()
        val vars = repository.get("tool.get_device_state").vars.associateWith { "x" }
        val outcome = repository.render("tool.get_device_state", vars)
        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
    }
}
