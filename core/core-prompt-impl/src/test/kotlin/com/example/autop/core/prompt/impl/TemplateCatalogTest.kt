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
        assertThat(toolTemplates).hasSize(47)
        for ((_, template) in toolTemplates) {
            for ((key, value) in template.meta) {
                assertThat(key).matches("param\\.[a-z0-9_]+")
                assertThat(value.trim()).isNotEmpty()
            }
        }
    }

    @Test
    fun `key templates match the vars documented in the plan`() {
        assertThat(templates.getValue("agent.state.env.time").vars)
            .containsExactly("localTime", "weekday", "timezone")
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
                "flow.author.analysis_system",
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
                "flow.recovery.reach_state",
                "flow.summary.cancelled",
                "flow.summary.failed",
                "flow.summary.succeeded",
                "tool.list_flows",
                "tool.run_flow",
                "tool.save_as_flow",
                "tool.update_flow",
                "tool.patch_flow_node",
                "tool.create_flow",
                "tool.edit_flow",
                "agent.budget.dimension.duration",
                "agent.budget.dimension.screenshots",
                "agent.budget.dimension.steps",
                "agent.budget.dimension.tokens",
                "agent.history.empty_tool_result",
                "agent.tool.arg_clamped",
                "agent.tool.skipped_hint.after_control",
                "agent.tool.skipped_hint.previous_failed",
                "agent.turn.empty_reply",
                "agent.turn.output_truncated",
                "agent.turn.truncated_call_hint",
                "agent.state.budget.dim.duration",
                "agent.state.budget.dim.screenshots",
                "agent.state.budget.dim.steps",
                "agent.state.budget.dim.tokens",
                "agent.state.budget.low",
                "agent.state.budget.remaining",
                "agent.state.delta",
                "agent.state.env.battery",
                "agent.state.env.foreground",
                "agent.state.env.network",
                "agent.state.env.screen",
                "agent.state.env.time",
                "agent.state.full",
                "agent.state.input.accessibility",
                "agent.state.input.shell",
                "agent.state.input.shield",
                "agent.state.navigation",
                "agent.state.observe.coord",
                "agent.state.observe.modes",
                "agent.state.observe.unavailable",
                "agent.state.perm.notification_listener",
                "agent.state.perm.overlay",
                "agent.state.perm.post_notifications",
                "agent.state.removed",
                "agent.state.tools.hidden",
                "agent.state.tools.reason.capability",
                "agent.state.tools.reason.disabled",
                "agent.state.tools.reason.filtered",
                "tool.note.saved",
                "tool.finish.received",
                "tool.history_recall.none",
                "tool.history_recall.truncated",
                "agent.breaker.same_error_stop",
                "agent.breaker.same_error_warn",
                "agent.budget.final_round",
                "agent.flow_directory",
                "agent.compaction.deterministic_note",
                "agent.compaction.handoff",
                "agent.compaction.last_observation",
                "agent.compaction.summary_wrapper",
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
                "agent.run.ended.breaker_stopped",
                "agent.run.ended.budget_exhausted",
                "agent.run.ended.cancelled",
                "agent.run.ended.expired",
                "agent.run.ended.failed",
                "agent.run.ended.interrupted",
                "agent.replay.diverged",
                "agent.resume.after_interrupt",
                "agent.resume.reobserve",
                "agent.system.output",
                "agent.system.role",
                "agent.system.run_mode.background",
                "agent.system.run_mode.foreground",
                "agent.system.safety",
                "agent.system.tool_guide",
                "agent.tool.cancelled_result",
                "agent.tool.error_result",
                "agent.tool.timeout_write_hint",
                "agent.tool.trace_header",
                "chat.title.generate",
                "device.hint.coord_out_of_range",
                "device.hint.intent_app_candidates",
                "device.hint.intent_map_not_installed",
                "device.hint.intent_no_handler",
                "device.hint.mode_unavailable",
                "device.a11y.tree_empty_hint",
                "device.input.refresh_failed_hint",
                "device.intent.ask_map",
                "device.intent.invalid",
                "device.intent.navigate_choice",
                "device.intent.result",
                "device.intent.settings_fallback",
                "device.launch.denied_hint",
                "device.launch.not_found_hint",
                "device.notifications.truncated",
                "device.visual.not_ready",
                "device.hint.node_mode_unavailable",
                "device.hint.node_no_observation",
                "device.hint.node_not_actionable",
                "device.hint.node_not_found",
                "device.hint.node_rejected",
                "device.hint.node_stale",
                "device.hit.no_tree",
                "device.hit.none",
                "device.action.wait_clamped",
                "device.action.waited",
                "device.input.append_empty",
                "device.input.clicked",
                "device.input.focus_not_editable",
                "device.input.no_focus",
                "device.input.no_focus_hint",
                "device.input.password_field",
                "device.input.password_field_hint",
                "device.input.result",
                "device.input.set_text_rejected",
                "device.input.set_text_rejected_hint",
                "device.input.text_required",
                "device.input.verb_append",
                "device.input.verb_clear",
                "device.input.verb_replace",
                "device.list_apps.truncated",
                "device.open_app.ambiguous_hint",
                "device.open_app.result",
                "device.restart.result",
                "device.restart.stopped",
                "device.scroll.dir_down",
                "device.scroll.dir_left",
                "device.scroll.dir_right",
                "device.scroll.dir_up",
                "device.scroll.partial",
                "device.scroll.result",
                "device.scroll.step",
                "device.swipe.duration_too_long",
                "device.swipe.result",
                "device.tap.result",
                "device.tap.verb_long",
                "device.tap.verb_tap",
                "device.global_action.failed_hint",
                "device.global_action.result",
                "device.node.result",
                "device.node.scroll_result",
                "device.observe.all_failed",
                "device.observe.auto_all_insufficient",
                "device.observe.auto_selected",
                "device.observe.insufficient",
                "device.observe.insufficient.few_nodes",
                "device.observe.insufficient.no_app_window",
                "device.observe.insufficient.no_interactive",
                "device.observe.insufficient.semantic_poor",
                "device.observe.no_auto_mode",
                "device.observation.coord",
                "device.screenshot.coord_note",
                "device.screenshot.region_note",
                "device.scroll.summary",
                "device.stabilize.not_settled",
                "device.visual.ocr_disabled",
                "device.trace.channel.accessibility",
                "device.trace.channel.none",
                "device.trace.channel.shell",
                "device.trace.gesture",
                "device.trace.global_action",
                "device.trace.node_action",
                "device.trace.shell_key",
                "device.trace.shell_skip.failed",
                "device.trace.shell_skip.not_connected",
                "llm.anthropic.leading_user",
                "llm.openai.tool_images_note",
                "memory.context.app_manual",
                "memory.context.index",
                "memory.context.profile",
                "memory.context.trigger_prefetch",
                "memory.experience.extract",
                "memory.summary.generate",
                "memory.write.supplement",
                "task.author.edit_context",
                "task.author.spec",
                "task.author.system",
                "task.author.tools",
                "task.condition.judge",
                "task.draft.fix_json",
                "task.draft.generate",
                "task.notify.denied_hint",
                "task.notify.sent",
                "tool.ask_user",
                "tool.clipboard_get",
                "tool.clipboard_set",
                "tool.create_task",
                "tool.finish",
                "tool.get_device_state",
                "tool.global_action",
                "tool.history_recall",
                "tool.input_text",
                "tool.intent",
                "tool.list_apps",
                "tool.list_tasks",
                "tool.memory_list",
                "tool.memory_read",
                "tool.memory_search",
                "tool.memory_write",
                "tool.note",
                "tool.notify_user",
                "tool.observe",
                "tool.observe.invalid_mode",
                "tool.observe.mode.a11y",
                "tool.observe.mode.visual",
                "tool.observe.mode_not_auto",
                "tool.observe.no_effective_mode",
                "tool.open_app",
                "tool.read_notifications",
                "tool.restart_app",
                "tool.screenshot",
                "tool.scroll",
                "tool.scroll_node",
                "tool.swipe",
                "tool.tap",
                "tool.tap_node",
                "tool.update_plan",
                "tool.update_task",
                "tool.wait",
            )
    }
}
