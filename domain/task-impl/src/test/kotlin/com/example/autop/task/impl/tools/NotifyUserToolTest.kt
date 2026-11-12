/**
 * notify_user（docs/design/tool_optimize/05 §2.1、06 §1.3）：
 * POST_NOTIFICATIONS 未授予时失败且不发通知；授予后发通知并返回成功。
 */
package com.example.autop.task.impl.tools

import android.app.NotificationManager
import android.content.Context
import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.PermissionChecks
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.impl.notify.UserNotifierImpl
import com.example.autop.task.impl.testing.FakeThreadStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** notify_user 权限门控测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class NotifyUserToolTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `without notification permission it fails and posts nothing`() =
        runTest {
            val tool = tool(FakePermissions(canPost = false))

            val out = tool.invoke(args(), ctx())

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(TaskErrorCodes.NOTIFICATION_DENIED)
            assertThat(shadowOf(notificationManager()).size()).isEqualTo(0)
        }

    @Test
    fun `with permission it posts the notification and succeeds`() =
        runTest {
            val tool = tool(FakePermissions(canPost = true))

            val out = tool.invoke(args(), ctx())

            assertThat(out.ok).isTrue()
            assertThat(out.content.toString()).contains("已发送通知：早报")
            assertThat(shadowOf(notificationManager()).size()).isEqualTo(1)
        }

    private fun tool(permissions: PermissionChecks): NotifyUserTool =
        NotifyUserTool(
            notifier = UserNotifierImpl(context, FakeConfigProvider()),
            permissions = permissions,
            prompts = FakePromptRepository(),
        )

    private fun args() =
        buildJsonObject {
            put("title", "早报")
            put("text", "天气晴")
        }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ctx(): ToolContext =
        object : ToolContext {
            override val runId: RunId = RunId("run-notify")

            override val threadId: ThreadId = ThreadId("thread-notify")

            override val config = FakeConfigProvider().current()

            override val runScope: RunScopedValues =
                object : RunScopedValues {
                    private val values = mutableMapOf<String, Any>()

                    @Suppress("UNCHECKED_CAST")
                    override fun <T : Any> getOrPut(
                        key: String,
                        create: () -> T,
                    ): T = values.getOrPut(key) { create() } as T
                }

            override val threadStore = FakeThreadStore()

            override suspend fun askUser(
                question: String,
                options: List<String>,
                timeoutMs: Long,
            ): Outcome<String> = Outcome.Err(AppError("test.unused", "askUser is not used"))

            override fun emitProgress(text: String) = Unit
        }

    private class FakePermissions(
        private val canPost: Boolean,
    ) : PermissionChecks {
        override fun canDrawOverlay(): Boolean = true

        override fun canPostNotifications(): Boolean = canPost

        override fun canReadNotifications(): Boolean = true

        override fun canUseFullScreenIntent(): Boolean = true

        override fun isIgnoringBatteryOptimizations(): Boolean = true

        override fun canScheduleExactAlarms(): Boolean = true
    }
}
