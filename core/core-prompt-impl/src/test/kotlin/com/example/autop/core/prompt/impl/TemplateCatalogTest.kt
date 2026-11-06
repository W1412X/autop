package com.example.autop.core.prompt.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 目录测试：真实模板与 docs/tech/09 §2 的清单逐项一致、全部可解析、工具模板 meta 完整。
 */
class TemplateCatalogTest {
    private val templates = loadBuiltinTemplates(loadRealPromptFiles())

    @Test
    fun `catalog matches the id list in the implementation plan`() {
        assertThat(templates.keys).containsExactlyElementsIn(EXPECTED_IDS)
    }

    @Test
    fun `every template has a non blank body`() {
        for (template in templates.values) {
            assertThat(template.body).isNotEmpty()
        }
    }

    @Test
    fun `tool templates describe every parameter in meta`() {
        val toolTemplates = templates.filterKeys { it.startsWith("tool.") }
        assertThat(toolTemplates).hasSize(35)
        for ((_, template) in toolTemplates) {
            for ((key, value) in template.meta) {
                assertThat(key).matches("param\\.[a-z0-9_]+")
                assertThat(value.trim()).isNotEmpty()
            }
        }
    }

    @Test
    fun `key templates match the vars documented in the plan`() {
        assertThat(templates.getValue("agent.context.environment").vars)
            .containsExactly(
                "localTime",
                "weekday",
                "timezone",
                "battery",
                "charging",
                "network",
                "foregroundApp",
                "locked",
            )
        assertThat(templates.getValue("agent.tool.error_result").vars).containsExactly("code", "message", "hint")
        assertThat(templates.getValue("agent.tool.error_result").optionalVars).containsExactly("hint")
        assertThat(templates.getValue("agent.compaction.summary_wrapper").vars)
            .containsExactly("fromSeq", "toSeq", "summary", "plan", "trigger")
        assertThat(
            templates.getValue("agent.compaction.summary_wrapper").optionalVars,
        ).containsExactly("plan", "trigger")
        assertThat(templates.getValue("memory.write.supplement").vars).containsExactly("date", "content")
        assertThat(templates.getValue("agent.replay.completed").vars).containsExactly("steps")
        assertThat(templates.getValue("agent.replay.diverged").vars).containsExactly("index", "reason", "remaining")
        assertThat(
            templates.getValue("task.draft.generate").vars,
        ).containsExactly("description", "sources", "now", "timezone")
    }

    @Test
    fun `anthropic leading user is the documented fixed text`() {
        val template = templates.getValue("llm.anthropic.leading_user")
        assertThat(template.vars).isEmpty()
        assertThat(template.body).isEqualTo("（继续）")
    }

    private companion object {
        /** 新方案模板 id（docs/design/observation-modes-coordinate-actions §4.4、§6；docs/tech/09 待更新）。 */
        val EXPECTED_IDS =
            setOf(
                "flow.author.demo_declined",
                "flow.author.draft_context",
                "flow.author.spec",
                "flow.author.system",
                "flow.author.tools",
                "flow.confirm.accept",
                "flow.confirm.question",
                "flow.confirm.skip",
                "flow.llm.json_only",
                "flow.llm.retry_feedback",
                "flow.llm.screen_section",
                "flow.llm.system",
                "flow.author.tool_cancelled",
                "flow.record.polish",
                "flow.recovery.handoff_goal",
                "flow.summary.cancelled",
                "flow.summary.failed",
                "flow.summary.succeeded",
                "tool.list_flows",
                "tool.run_flow",
                "tool.save_as_flow",
                "tool.update_flow",
                "tool.patch_flow_node",
                "agent.breaker.repeat_warn",
                "agent.breaker.same_error_stop",
                "agent.breaker.same_error_warn",
                "agent.breaker.stuck_stop",
                "agent.breaker.stuck_warn",
                "agent.budget.final_round",
                "agent.flow_directory",
                "agent.compaction.deterministic_note",
                "agent.compaction.handoff",
                "agent.compaction.last_observation",
                "agent.compaction.summary_wrapper",
                "agent.context.environment",
                "agent.context.goal",
                "agent.context.trigger",
                "agent.fallback.json_protocol",
                "agent.fallback.parse_error",
                "agent.fallback.tool_result",
                "agent.finish.deferred_by_steer",
                "agent.history.diff_base_missing",
                "agent.history.image_stub",
                "agent.history.observation_stub",
                "agent.history.truncated",
                "agent.notes.block",
                "agent.plan.unfinished_items",
                "agent.policy.blocked_hint",
                "agent.replay.completed",
                "agent.replay.diverged",
                "agent.resume.after_interrupt",
                "agent.resume.reobserve",
                "agent.system.role",
                "agent.system.run_mode.background",
                "agent.system.run_mode.foreground",
                "agent.system.safety",
                "agent.system.tool_guide",
                "agent.tool.cancelled_result",
                "agent.tool.error_result",
                "chat.title.generate",
                "device.hint.coord_out_of_range",
                "device.hint.llm_parse_failed",
                "device.hint.mode_unavailable",
                "device.llm_parser.coord.normalized_xyxy",
                "device.llm_parser.coord.normalized_yxyx",
                "device.llm_parser.coord.pixels_xyxy",
                "device.llm_parser.coord.pixels_yxyx",
                "device.llm_parser.example.normalized_xyxy",
                "device.llm_parser.example.normalized_yxyx",
                "device.llm_parser.example.pixels_xyxy",
                "device.llm_parser.example.pixels_yxyx",
                "device.llm_parser.json_only",
                "device.llm_parser.system",
                "device.llm_parser.user",
                "device.observation.coord",
                "device.observation.suggest_mode",
                "device.screenshot.coord_note",
                "device.screenshot.region_note",
                "llm.anthropic.leading_user",
                "llm.openai.tool_images_note",
                "memory.context.app_manual",
                "memory.context.index",
                "memory.context.profile",
                "memory.context.trigger_prefetch",
                "memory.experience.extract",
                "memory.summary.generate",
                "memory.write.supplement",
                "task.condition.judge",
                "task.draft.fix_json",
                "task.draft.generate",
                "tool.ask_user",
                "tool.clipboard_get",
                "tool.clipboard_set",
                "tool.dismiss_popup",
                "tool.finish",
                "tool.get_device_state",
                "tool.global_action",
                "tool.history_recall",
                "tool.input_text",
                "tool.list_apps",
                "tool.memory_list",
                "tool.memory_read",
                "tool.memory_search",
                "tool.memory_write",
                "tool.note",
                "tool.notify_user",
                "tool.observe",
                "tool.observe.mode.a11y",
                "tool.observe.mode.llm",
                "tool.observe.mode.visual",
                "tool.open_app",
                "tool.open_uri",
                "tool.read_notifications",
                "tool.restart_app",
                "tool.screenshot",
                "tool.scroll",
                "tool.swipe",
                "tool.tap",
                "tool.update_plan",
                "tool.wait",
            )
    }
}
