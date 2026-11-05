/**
 * 任务域路由图挂载入口（docs/tech/08 §4.3）：`tasks/list`、`tasks/edit/{id?}`、
 * `tasks/detail/{id}`、`runs/{runId}`。
 */
package com.example.autop.feature.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.autop.feature.tasks.detail.TaskDetailScreen
import com.example.autop.feature.tasks.detail.TaskDetailViewModel
import com.example.autop.feature.tasks.edit.TaskEditScreen
import com.example.autop.feature.tasks.edit.TaskEditViewModel
import com.example.autop.feature.tasks.list.TasksListScreen
import com.example.autop.feature.tasks.list.TasksListViewModel
import com.example.autop.feature.tasks.run.RunDetailScreen
import com.example.autop.feature.tasks.run.RunDetailViewModel

/** `tasks/edit/{taskId}` 路由模板的参数名（app 的 `AutopRoutes` 组装模板时引用）。 */
public const val TASK_ID_ARG: String = "taskId"

/** `runs/{runId}` 路由模板的参数名。 */
public const val RUN_ID_ARG: String = "runId"

/** 新建任务时的路由参数占位值。 */
public const val NEW_TASK_ID: String = "new"

/**
 * 挂载任务域四路由（docs/tech/08 §4.3）。
 *
 * @param listRoute 列表路由（app 的 `AutopRoutes.TASKS_LIST`）。
 * @param editRoute 编辑路由模板（app 的 `AutopRoutes.TASK_EDIT`）。
 * @param detailRoute 详情路由模板（app 的 `AutopRoutes.TASK_DETAIL`）。
 * @param runRoute Run 详情路由模板（app 的 `AutopRoutes.RUN_DETAIL`）。
 * @param navigator 导航回调。
 */
public fun NavGraphBuilder.tasksGraph(
    listRoute: String,
    editRoute: String,
    detailRoute: String,
    runRoute: String,
    navigator: TasksNavigator,
) {
    composable(listRoute) {
        TasksListRoute(navigator = navigator)
    }
    composable(editRoute) {
        TaskEditRoute(navigator = navigator)
    }
    composable(detailRoute) {
        TaskDetailRoute(navigator = navigator)
    }
    composable(runRoute) {
        RunDetailRoute(navigator = navigator)
    }
}

@Composable
private fun TasksListRoute(
    navigator: TasksNavigator,
    viewModel: TasksListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TasksListScreen(
        state = state,
        onAction = { action -> viewModel.onAction(action, navigator) },
    )
}

@Composable
private fun TaskEditRoute(
    navigator: TasksNavigator,
    viewModel: TaskEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TaskEditScreen(
        state = state,
        onAction = { action -> viewModel.onAction(action, navigator) },
    )
}

@Composable
private fun TaskDetailRoute(
    navigator: TasksNavigator,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TaskDetailScreen(
        state = state,
        onAction = { action -> viewModel.onAction(action, navigator) },
    )
}

@Composable
private fun RunDetailRoute(
    navigator: TasksNavigator,
    viewModel: RunDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RunDetailScreen(
        state = state,
        onAction = { action -> viewModel.onAction(action, navigator) },
    )
}
