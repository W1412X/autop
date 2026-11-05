/**
 * 字符串路由集中表（docs/tech/08 §3：路由集中在 app 的 `AutopRoutes`）。
 *
 * feature 模块导航一律引用这里的常量/构造函数，不自造路由字符串。
 */
package com.example.autop

import android.net.Uri
import com.example.autop.feature.chat.THREAD_ARG
import com.example.autop.feature.memory.MEMORY_PATH_ARG
import com.example.autop.feature.onboarding.ONBOARDING_MODE_ARG
import com.example.autop.feature.onboarding.ONBOARDING_MODE_HEALTH
import com.example.autop.feature.settings.ME_SECTION_ARG
import com.example.autop.feature.tasks.NEW_TASK_ID
import com.example.autop.feature.tasks.RUN_ID_ARG
import com.example.autop.feature.tasks.TASK_ID_ARG

/** 全部应用路由。 */
object AutopRoutes {
    /** 对话列表顶级页（起始页）。 */
    const val CHAT_LIST: String = "chat/list"

    /** 任务列表顶级页。 */
    const val TASKS_LIST: String = "tasks/list"

    /** 记忆浏览顶级页起始路由（根目录，docs/tech/08 §3 起始路由表）。 */
    const val MEMORY_BROWSE_START: String = "memory/browse?path=/"

    /** 我的顶级页。 */
    const val ME: String = "me"

    /** 引导页（feature-onboarding）。 */
    const val ONBOARDING: String = "onboarding"

    /** 引导页路由模板（docs/tech/08 §4.7 可选 `mode` 参数；参数名取自 feature-onboarding 的 [ONBOARDING_MODE_ARG]）。 */
    val ONBOARDING_ROUTE: String = "onboarding?${ONBOARDING_MODE_ARG}={${ONBOARDING_MODE_ARG}}"

    /** 记忆浏览路由模板（docs/tech/08 §4.4 `memory/browse?path=`；参数名取自 feature-memory 的 [MEMORY_PATH_ARG]）。 */
    val MEMORY_BROWSE: String = "memory/browse?path={${MEMORY_PATH_ARG}}"

    /** 记忆条目编辑路由模板（docs/tech/08 §4.4 `memory/node?path=`）。 */
    val MEMORY_NODE: String = "memory/node?path={${MEMORY_PATH_ARG}}"

    /** 记忆最近变更页（docs/tech/08 §4.4 `memory/changes`）。 */
    const val MEMORY_CHANGES: String = "memory/changes"

    /** "我的"子页路由模板（docs/tech/08 §4.5 `me/{id}`；参数名取自 feature-settings 的 [ME_SECTION_ARG]）。 */
    val ME_SECTION: String = "me/{${ME_SECTION_ARG}}"

    /** 对话页路由模板（docs/tech/08 §4.2 `chat/thread/{threadId}`；参数名取自 feature-chat 的 [THREAD_ARG]）。 */
    val CHAT_THREAD: String = "chat/thread/{${THREAD_ARG}}"

    /** 任务编辑路由模板（docs/tech/08 §4.3 `tasks/edit/{taskId}`；参数名取自 feature-tasks 的 [TASK_ID_ARG]）。 */
    val TASK_EDIT: String = "tasks/edit/{${TASK_ID_ARG}}"

    /** 任务详情路由模板（docs/tech/08 §4.3 `tasks/detail/{taskId}`）。 */
    val TASK_DETAIL: String = "tasks/detail/{${TASK_ID_ARG}}"

    /** Run 详情路由模板（docs/tech/08 §4.3 `runs/{runId}`；参数名取自 feature-tasks 的 [RUN_ID_ARG]）。 */
    val RUN_DETAIL: String = "runs/{${RUN_ID_ARG}}"

    /** 记忆浏览路由：[path] 经 URL 编码（如 `memory/browse?path=%2Fnotes`）。 */
    fun memoryBrowse(path: String = "/"): String = "memory/browse?path=${Uri.encode(path)}"

    /** 记忆条目编辑路由：[path] 经 URL 编码（如 `memory/node?path=%2Fpeople`）。 */
    fun memoryNode(path: String): String = "memory/node?path=${Uri.encode(path)}"

    /** "我的"子页路由：[id] 见 feature-settings 的 `SettingsSection.id`。 */
    fun meSection(id: String): String = "me/$id"

    /** 健康检查页路由（docs/tech/08 §4.5 权限检查入口，`onboarding?mode=health`）。 */
    fun onboardingHealth(): String = "onboarding?${ONBOARDING_MODE_ARG}=$ONBOARDING_MODE_HEALTH"

    /** 对话页路由：[threadId] 取 feature-chat 的 `NEW_THREAD`（新对话）或真实线程 id。 */
    fun chatThread(threadId: String): String = "chat/thread/$threadId"

    /** 任务编辑路由：[taskId] 取 feature-tasks 的 [NEW_TASK_ID]（新建）或真实任务 id。 */
    fun taskEdit(taskId: String = NEW_TASK_ID): String = "tasks/edit/$taskId"

    /** 任务详情路由。 */
    fun taskDetail(taskId: String): String = "tasks/detail/$taskId"

    /** Run 详情路由。 */
    fun runDetail(runId: String): String = "runs/$runId"
}
