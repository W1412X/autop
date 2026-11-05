/**
 * [RulesViewModel] 单测：用户规则增删改 → `policy.userRules` 整表覆盖写入，
 * 表单校验错误与写入失败路径（docs/tech/08 §4.5 me/rules）。
 */
package com.example.autop.feature.settings.me.rules

import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.PolicyConfig
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.feature.settings.me.RecordingConfigEditor
import com.example.autop.feature.settings.me.RecordingLogger
import com.example.autop.policy.PolicyRule
import com.example.autop.policy.RuleScope
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModelTest {
    private val provider = FakeConfigProvider()
    private val editor = RecordingConfigEditor()
    private val logger = RecordingLogger()
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `user rules rows keep parsed and raw entries`() =
        runTest(testDispatcher) {
            provider.setOverrides(userRulesOverrides(ruleJson("r1"), buildJsonObject { put("bad", true) }))
            val state = createViewModel().uiState.value
            assertThat(state.user).hasSize(2)
            assertThat(state.user[0].id).isEqualTo("r1")
            assertThat(state.user[1].id).isNull()
            assertThat(state.user[1].raw).isNotNull()
        }

    @Test
    fun `add rule writes whole user rules list`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(RulesAction.AddRule)
            viewModel.onAction(RulesAction.SetField(validDraft()))
            viewModel.onAction(RulesAction.SaveRule)
            val (path, written) = editor.writes.single()
            assertThat(path).isEqualTo(PolicyConfig.POLICY.path + ".userRules")
            val rules = (written as JsonArray).map { it.jsonObject }
            assertThat(rules).hasSize(1)
            assertThat(rules.single()["id"]).isEqualTo(AutopJson.default.parseToJsonElement("\"r1\""))
            assertThat(viewModel.uiState.value.editing).isNull()
        }

    @Test
    fun `edit rule replaces entry with same original id`() =
        runTest(testDispatcher) {
            provider.setOverrides(userRulesOverrides(ruleJson("r1"), ruleJson("r2")))
            val viewModel = createViewModel()
            viewModel.onAction(RulesAction.EditRule("r1"))
            val draft = checkNotNull(viewModel.uiState.value.editing)
            viewModel.onAction(RulesAction.SetField(draft.copy(category = "security")))
            viewModel.onAction(RulesAction.SaveRule)
            val rules = (editor.writes.single().second as JsonArray).map { it.jsonObject }
            assertThat(rules.map { it["id"] }).hasSize(2)
            assertThat(rules.first()["category"]).isEqualTo(AutopJson.default.parseToJsonElement("\"security\""))
            assertThat(rules.last()["category"]).isEqualTo(AutopJson.default.parseToJsonElement("\"payment\""))
        }

    @Test
    fun `delete rule rewrites list without the row`() =
        runTest(testDispatcher) {
            provider.setOverrides(userRulesOverrides(ruleJson("r1"), ruleJson("r2")))
            val viewModel = createViewModel()
            viewModel.onAction(RulesAction.DeleteRule(0))
            val rules = (editor.writes.single().second as JsonArray).map { it.jsonObject }
            assertThat(rules.map { it["id"] }).containsExactly(AutopJson.default.parseToJsonElement("\"r2\""))
        }

    @Test
    fun `duplicate id is rejected without write`() =
        runTest(testDispatcher) {
            provider.setOverrides(userRulesOverrides(ruleJson("r1")))
            val viewModel = createViewModel()
            viewModel.onAction(RulesAction.AddRule)
            viewModel.onAction(RulesAction.SetField(validDraft()))
            viewModel.onAction(RulesAction.SaveRule)
            assertThat(editor.writes).isEmpty()
            assertThat(viewModel.uiState.value.error).isEqualTo(RulesError.ID_INVALID)
        }

    @Test
    fun `empty text any keywords are rejected`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(RulesAction.AddRule)
            viewModel.onAction(RulesAction.SetField(validDraft().copy(values = "")))
            viewModel.onAction(RulesAction.SaveRule)
            assertThat(viewModel.uiState.value.error).isEqualTo(RulesError.PARAMS_EMPTY)
        }

    @Test
    fun `invalid min hits is rejected`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(RulesAction.AddRule)
            viewModel.onAction(RulesAction.SetField(validDraft().copy(minHits = "0")))
            viewModel.onAction(RulesAction.SaveRule)
            assertThat(viewModel.uiState.value.error).isEqualTo(RulesError.MIN_HITS_INVALID)
        }

    @Test
    fun `write failure surfaces save failed and logs`() =
        runTest(testDispatcher) {
            editor.result = Outcome.Err(AppError(code = "io.failed", message = "disk"))
            val viewModel = createViewModel()
            viewModel.onAction(RulesAction.AddRule)
            viewModel.onAction(RulesAction.SetField(validDraft()))
            viewModel.onAction(RulesAction.SaveRule)
            testScheduler.advanceUntilIdle()
            assertThat(viewModel.uiState.value.error).isEqualTo(RulesError.SAVE_FAILED)
            assertThat(logger.errors).isNotEmpty()
        }

    private fun createViewModel() = RulesViewModel(provider, editor, TestDispatcherProvider(testDispatcher), logger)

    private fun validDraft(): RuleDraft =
        RuleDraft(
            id = "r1",
            type = RuleDraft.TYPE_TEXT_ANY,
            category = "payment",
            scope = RuleScope.TARGET.name,
            values = "确认\n付款",
        )
}

private fun ruleJson(id: String): JsonObject =
    AutopJson.default
        .encodeToJsonElement(
            PolicyRule.serializer(),
            PolicyRule(
                id = id,
                type = RuleDraft.TYPE_TEXT_ANY,
                category = "payment",
                scope = RuleScope.TARGET,
                params =
                    buildJsonObject {
                        put("keywords", buildJsonArray { add(AutopJson.default.parseToJsonElement("\"付款\"")) })
                    },
            ),
        ).jsonObject

private fun userRulesOverrides(vararg rules: JsonObject): JsonObject =
    buildJsonObject {
        put(
            "policy",
            buildJsonObject { put("userRules", JsonArray(rules.toList())) },
        )
    }
