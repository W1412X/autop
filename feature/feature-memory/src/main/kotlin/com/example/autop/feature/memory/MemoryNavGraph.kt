/**
 * `memory` 路由图挂载入口（docs/tech/08 §3/§4.4）：app 的 NavHost 调用。
 * 三个路由：`memory/browse?path=`（浏览）、`memory/node?path=`（条目编辑）、`memory/changes`（最近变更）。
 */
package com.example.autop.feature.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.autop.feature.memory.browse.BrowseScreen
import com.example.autop.feature.memory.browse.BrowseViewModel
import com.example.autop.feature.memory.changes.ChangesScreen
import com.example.autop.feature.memory.changes.ChangesViewModel
import com.example.autop.feature.memory.node.NodeScreen
import com.example.autop.feature.memory.node.NodeViewModel

/** 浏览与条目路由的路径参数名。 */
public const val MEMORY_PATH_ARG: String = "path"

/**
 * 挂载记忆浏览、条目编辑与最近变更三页（docs/tech/08 §4.4）。
 *
 * @param browseRoute 浏览路由模板（app 的 `AutopRoutes.MEMORY_BROWSE`）。
 * @param nodeRoute 条目路由模板（app 的 `AutopRoutes.MEMORY_NODE`）。
 * @param changesRoute 最近变更路由（app 的 `AutopRoutes.MEMORY_CHANGES`）。
 * @param navigator 记忆域导航回调。
 */
public fun NavGraphBuilder.memoryGraph(
    browseRoute: String,
    nodeRoute: String,
    changesRoute: String,
    navigator: MemoryNavigator,
) {
    composable(browseRoute) {
        BrowseRoute(navigator = navigator)
    }
    composable(nodeRoute) {
        NodeRoute(navigator = navigator)
    }
    composable(changesRoute) {
        ChangesRoute(navigator = navigator)
    }
}

@Composable
private fun BrowseRoute(
    navigator: MemoryNavigator,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BrowseScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenNode = navigator::openNode,
        onOpenChanges = navigator::openChanges,
    )
}

@Composable
private fun NodeRoute(
    navigator: MemoryNavigator,
    viewModel: NodeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NodeScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = navigator::navigateBack,
    )
}

@Composable
private fun ChangesRoute(
    navigator: MemoryNavigator,
    viewModel: ChangesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChangesScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = navigator::navigateBack,
        onOpenNode = navigator::openNode,
    )
}
