/**
 * `chat/list` 与 `chat/thread/{threadId}` 路由图挂载入口（docs/tech/08 §3/§4.1/§4.2）：
 * app 的 NavHost 调用。导航类 [com.example.autop.feature.chat.list.ChatListAction]
 * 在 Route 层拦截转 [ChatNavigator]；对话页的一次性导航结果走
 * [com.example.autop.feature.chat.thread.ChatThreadViewModel.navEvents]。
 */
package com.example.autop.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.autop.feature.chat.list.ChatListAction
import com.example.autop.feature.chat.list.ChatListScreen
import com.example.autop.feature.chat.list.ChatListViewModel
import com.example.autop.feature.chat.thread.ChatNavEvent
import com.example.autop.feature.chat.thread.ChatThreadScreen
import com.example.autop.feature.chat.thread.ChatThreadViewModel

/** `chat/thread/{threadId}` 路由模板的参数名（app 的 `AutopRoutes` 组装模板时引用）。 */
public const val THREAD_ARG: String = "threadId"

/** 未建线程的新对话页标记（首次发送后经 [ChatNavigator.replaceThread] 换成真实 id）。 */
public const val NEW_THREAD: String = "new"

/**
 * 挂载对话列表页与对话页（docs/tech/08 §4.1/§4.2）。
 *
 * @param route 对话列表路由（app 的 `AutopRoutes.CHAT_LIST`）。
 * @param threadRoute 对话页路由模板（app 的 `AutopRoutes.CHAT_THREAD`，含 [THREAD_ARG]）。
 * @param navigator 导航回调。
 */
public fun NavGraphBuilder.chatGraph(
    route: String,
    threadRoute: String,
    navigator: ChatNavigator,
) {
    composable(route) {
        ChatListRoute(navigator = navigator)
    }
    composable(threadRoute) { entry ->
        ChatThreadRoute(
            threadId = entry.arguments?.getString(THREAD_ARG),
            navigator = navigator,
        )
    }
}

@Composable
private fun ChatListRoute(
    navigator: ChatNavigator,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChatListScreen(
        state = state,
        onAction = { action ->
            when (action) {
                ChatListAction.NewChat -> navigator.openThread(NEW_THREAD)
                is ChatListAction.OpenThread -> navigator.openThread(action.threadId)
                ChatListAction.EnableAccessibility -> navigator.openAccessibilitySettings()
                else -> viewModel.onAction(action)
            }
        },
    )
}

@Composable
private fun ChatThreadRoute(
    threadId: String?,
    navigator: ChatNavigator,
    viewModel: ChatThreadViewModel = hiltViewModel(),
) {
    LaunchedEffect(threadId) {
        viewModel.bindThread(threadId ?: NEW_THREAD)
    }
    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is ChatNavEvent.ReplaceThread -> navigator.replaceThread(event.threadId)
                ChatNavEvent.NavigateBack -> navigator.navigateBack()
                ChatNavEvent.Background -> navigator.backgroundApp()
                is ChatNavEvent.ExportLog -> navigator.shareExport(event.text)
                is ChatNavEvent.OpenLink -> navigator.openStepLink(event.link)
            }
        }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChatThreadScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = navigator::navigateBack,
    )
}
