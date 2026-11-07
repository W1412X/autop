/**
 * [FlowVersionsViewModel] 单测：版本加载、当前标记、回滚调用链、变更信号刷新。
 */
package com.example.autop.feature.flows.versions

import androidx.lifecycle.SavedStateHandle
import com.example.autop.core.common.FlowId
import com.example.autop.feature.flows.FLOW_ID_ARG
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowVersion
import com.example.autop.flow.StoredFlow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowVersionsViewModelTest {
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
    fun `versions are loaded and current is marked`() =
        runTest(testDispatcher) {
            val defA = FlowDefinition(id = FLOW_ID, name = "流程A")
            val defB = FlowDefinition(id = FLOW_ID, name = "流程B")
            val flows = FakeFlowStore(StoredFlow(FlowId(FLOW_ID), defB, 0L, 1L))
            val versions = FakeFlowVersionStore(flows)
            versions.versions += versionOf(FLOW_ID, 1, defA, "初次保存")
            versions.versions += versionOf(FLOW_ID, 2, defB, "改名")
            val vm = createViewModel(flows, versions)

            assertThat(vm.uiState.value.versions).hasSize(2)
            // defB 与当前定义一致，应标记为当前版本
            val list = vm.uiState.value.versions
            val v2 = list.first { it.version == 2 }
            val v1 = list.first { it.version == 1 }
            assertThat(v2.isCurrent).isTrue()
            assertThat(v1.isCurrent).isFalse()
        }

    @Test
    fun `restore calls versionStore and refreshes`() =
        runTest(testDispatcher) {
            val defA = FlowDefinition(id = FLOW_ID, name = "旧版")
            val defB = FlowDefinition(id = FLOW_ID, name = "新版")
            val flows = FakeFlowStore(StoredFlow(FlowId(FLOW_ID), defB, 0L, 1L))
            val versions = FakeFlowVersionStore(flows)
            versions.versions += versionOf(FLOW_ID, 1, defA, "初次")
            val vm = createViewModel(flows, versions)

            vm.onAction(FlowVersionsAction.RequestRestore(1), fakeNavigator())
            vm.onAction(FlowVersionsAction.ConfirmRestore, fakeNavigator())

            assertThat(versions.restoreCount).isEqualTo(1)
            assertThat(flows.current?.definition).isEqualTo(defA)
            assertThat(vm.uiState.value.restoredVersion).isEqualTo(1)
            // 列表刷新走变更信号（restore 内部写流程定义触发 observeAll），需等去抖
            advanceTimeBy(DEBOUNCE_WAIT_MS)
            assertThat(vm.uiState.value.versions).isNotEmpty()
        }

    @Test
    fun `flow change signal refreshes version list`() =
        runTest(testDispatcher) {
            val defA = FlowDefinition(id = FLOW_ID, name = "v1")
            val flows = FakeFlowStore(StoredFlow(FlowId(FLOW_ID), defA, 0L, 1L))
            val versions = FakeFlowVersionStore(flows)
            val vm = createViewModel(flows, versions)
            assertThat(vm.uiState.value.versions).isEmpty()

            // 模拟编辑器保存：追加一条版本记录并触发变更信号
            versions.versions += versionOf(FLOW_ID, 1, defA, "编辑保存")
            flows.emitChange()
            advanceTimeBy(DEBOUNCE_WAIT_MS)

            assertThat(vm.uiState.value.versions).hasSize(1)
        }

    private fun createViewModel(
        flows: FakeFlowStore,
        versions: FakeFlowVersionStore,
    ): FlowVersionsViewModel =
        FlowVersionsViewModel(
            savedStateHandle = SavedStateHandle(mapOf(FLOW_ID_ARG to FLOW_ID)),
            store = flows,
            versionStore = versions,
        )

    private fun versionOf(
        flowId: String,
        version: Int,
        definition: FlowDefinition,
        reason: String?,
    ): FlowVersion = FlowVersion(flowId, version, definition, 0L, reason)

    private fun fakeNavigator(): FlowsNavigator =
        object : FlowsNavigator {
            override fun openFlow(flowId: String) = Unit

            override fun openEditor(flowId: String) = Unit

            override fun openAuthor(
                draftId: String?,
                editFlowId: String?,
            ) = Unit

            override fun openCreate() = Unit

            override fun openRunRecord(runId: String) = Unit

            override fun openEndpoints() = Unit

            override fun openState(flowId: String) = Unit

            override fun openVersions(flowId: String) = Unit

            override fun shareText(text: String) = Unit

            override fun navigateBack() = Unit
        }

    private companion object {
        const val FLOW_ID = "flow-1"

        /** 略大于 REFRESH_DEBOUNCE_MS（300ms），确保去抖后的刷新已执行。 */
        const val DEBOUNCE_WAIT_MS = 500L
    }
}
