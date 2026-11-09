/**
 * 控制条变体文案（hintTitleOf / pickVariant）：状态短语、工具变体、加权反重复抽取。
 */
package com.example.autop.feature.overlay.indicator

import com.example.autop.agent.RunState
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RunHintVariantsTest {
    @Test
    fun `空池返回 null`() {
        assertThat(pickVariant(emptyList(), null, 0f)).isNull()
    }

    @Test
    fun `上一次说法降权，其他说法概率更大`() {
        val pool = listOf("a", "b", "c")
        // previous="a" 权重 1:2:2（占 1/5），roll 落点按累计权重
        assertThat(pickVariant(pool, "a", 0.1f)).isEqualTo("a")
        assertThat(pickVariant(pool, "a", 0.3f)).isEqualTo("b")
        assertThat(pickVariant(pool, "a", 0.9f)).isEqualTo("c")
    }

    @Test
    fun `上一次不在池中则均匀抽取`() {
        val pool = listOf("a", "b")
        assertThat(pickVariant(pool, "z", 0.25f)).isEqualTo("a")
        assertThat(pickVariant(pool, "z", 0.75f)).isEqualTo("b")
    }

    @Test
    fun `思考准备整理状态短语`() {
        assertThat(hintTitleOf(active().copy(state = RunState.SAMPLING), null, 0f)).isEqualTo("思考中…")
        assertThat(hintTitleOf(active().copy(state = RunState.STARTING), null, 0f)).isEqualTo("准备中…")
        assertThat(hintTitleOf(active().copy(state = RunState.COMPACTING), null, 0f)).isEqualTo("整理上下文中…")
    }

    @Test
    fun `工具变体带目标与无目标`() {
        val withTarget = active().copy(toolId = "tool.tap", toolTarget = "存储")
        assertThat(hintTitleOf(withTarget, null, 0f)).isEqualTo("正在点击「存储」")
        assertThat(hintTitleOf(active().copy(toolId = "tool.tap"), null, 0f)).isEqualTo("点击中…")
    }

    @Test
    fun `未知工具不裸显工具名`() {
        assertThat(hintTitleOf(active().copy(toolId = "tool.mystery"), null, 0f)).isEqualTo("正在执行 mystery…")
    }

    @Test
    fun `模型可见裸名与工具 id 同等命中`() {
        // ToolCall.name 是模型可见裸名（无 tool. 前缀），变体表须两形态都查中
        val bare = active().copy(toolId = "tap", toolTarget = "存储")
        assertThat(hintTitleOf(bare, null, 0f)).isEqualTo("正在点击「存储」")
        assertThat(hintTitleOf(active().copy(toolId = "global_action", toolTarget = "返回"), null, 0f))
            .isEqualTo("返回中…")
    }

    @Test
    fun `无工具回落基础文案`() {
        assertThat(hintTitleOf(active().copy(currentStep = "点击「存储」"), null, 0f)).isEqualTo("点击「存储」")
        assertThat(hintTitleOf(active(), null, 0f)).isEqualTo("运行中")
    }

    private fun active(): ActiveRunInfo =
        ActiveRunInfo(
            run =
                RunRecord(
                    id = RunId("run-1"),
                    taskId = null,
                    threadId = ThreadId("thread-1"),
                    origin = RunOrigin.CHAT,
                    status = RunStatus.RUNNING,
                    triggerContext = emptyMap(),
                    createdAt = 0L,
                    startedAt = null,
                    endedAt = null,
                    summary = null,
                    errorCode = null,
                    pauseReason = null,
                    waitingQuestion = null,
                    waitingOptions = emptyList(),
                ),
            state = RunState.EXECUTING_TOOLS,
            currentStep = null,
            stepCount = 1,
            plan = emptyList(),
        )
}
