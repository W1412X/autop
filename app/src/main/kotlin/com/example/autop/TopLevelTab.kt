/**
 * 顶级页 Tab 定义（docs/tech/08 §3）：底栏四项，图标 Outlined 未选中 / Filled 选中。
 */
package com.example.autop

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.autop.core.ui.component.AutopIcons

internal enum class TopLevelTab(
    val route: String,
    /** 路由模板（[route] 是带参路由的具体值时，底栏选中态用模板比对当前 destination.route）。 */
    val routePattern: String = route,
    @get:StringRes val titleRes: Int,
    val icon: ImageVector,
    val iconSelected: ImageVector,
) {
    CHAT(
        route = AutopRoutes.CHAT_LIST,
        titleRes = R.string.nav_chat,
        icon = AutopIcons.ChatBubbleOutline,
        iconSelected = AutopIcons.ChatBubble,
    ),
    TASKS(
        route = AutopRoutes.TASKS_LIST,
        titleRes = R.string.nav_tasks,
        icon = AutopIcons.EventRepeat,
        iconSelected = AutopIcons.EventRepeatFilled,
    ),
    MEMORY(
        route = AutopRoutes.MEMORY_BROWSE_START,
        routePattern = AutopRoutes.MEMORY_BROWSE,
        titleRes = R.string.nav_memory,
        icon = AutopIcons.Psychology,
        iconSelected = AutopIcons.PsychologyFilled,
    ),
    ME(
        route = AutopRoutes.ME,
        titleRes = R.string.nav_me,
        icon = AutopIcons.Person,
        iconSelected = AutopIcons.PersonFilled,
    ),
}
