/**
 * 流程域路由图挂载入口：详情、编辑器、AI 创建、创建入口、执行记录、接口登记、状态管理。
 * 路由字符串由 app 的 `AutopRoutes` 集中定义；「流程」页签内容见 [com.example.autop.feature.flows.list.FlowsListContent]。
 */
@file:Suppress("MatchingDeclarationName") // 路由图入口与其路由集合同文件

package com.example.autop.feature.flows

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.autop.feature.flows.author.FlowAuthorScreen
import com.example.autop.feature.flows.author.FlowAuthorViewModel
import com.example.autop.feature.flows.create.FlowCreateScreen
import com.example.autop.feature.flows.create.FlowCreateViewModel
import com.example.autop.feature.flows.detail.FlowDetailScreen
import com.example.autop.feature.flows.detail.FlowDetailViewModel
import com.example.autop.feature.flows.editor.FlowEditorScreen
import com.example.autop.feature.flows.editor.FlowEditorViewModel
import com.example.autop.feature.flows.endpoints.EndpointsScreen
import com.example.autop.feature.flows.endpoints.EndpointsViewModel
import com.example.autop.feature.flows.record.FlowRecordScreen
import com.example.autop.feature.flows.record.FlowRecordViewModel
import com.example.autop.feature.flows.state.FlowStateScreen
import com.example.autop.feature.flows.state.FlowStateViewModel

/** 流程域的路由模板（由 app 的 `AutopRoutes` 组装）。 */
public class FlowsRoutes(
    public val detail: String,
    public val editor: String,
    public val author: String,
    public val create: String,
    public val record: String,
    public val endpoints: String,
    public val state: String,
)

/** 挂载流程域全部页面。 */
public fun NavGraphBuilder.flowsGraph(
    routes: FlowsRoutes,
    navigator: FlowsNavigator,
) {
    composable(routes.detail) {
        val vm: FlowDetailViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        FlowDetailScreen(state = state, onAction = { vm.onAction(it, navigator) })
    }
    composable(routes.editor) {
        val vm: FlowEditorViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        FlowEditorScreen(state = state, onAction = { vm.onAction(it, navigator) })
    }
    composable(routes.author) {
        val vm: FlowAuthorViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        FlowAuthorScreen(state = state, onAction = { vm.onAction(it, navigator) })
    }
    composable(routes.create) {
        val vm: FlowCreateViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        FlowCreateScreen(state = state, onAction = { vm.onAction(it, navigator) })
    }
    composable(routes.record) {
        val vm: FlowRecordViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        FlowRecordScreen(state = state, onAction = { vm.onAction(it, navigator) })
    }
    composable(routes.endpoints) {
        val vm: EndpointsViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        EndpointsScreen(state = state, onAction = { vm.onAction(it, navigator) })
    }
    composable(routes.state) {
        val vm: FlowStateViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        FlowStateScreen(state = state, onAction = { vm.onAction(it, navigator) })
    }
}
