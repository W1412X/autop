/**
 * read_notifications / clipboard_get（docs/design/tool_optimize/02 §12、§14、06 §1.3）：
 * 外部文本统一包在 `<untrusted>` 信封中。
 */
package com.example.autop.device.impl.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.example.autop.core.common.PermissionChecks
import com.example.autop.core.testing.FakeClock
import com.example.autop.device.NotificationFeed
import com.example.autop.device.NotificationInfo
import com.example.autop.device.NotificationMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReadNotificationsToolTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `notification texts are wrapped as untrusted`() =
        runTest {
            val env = ToolTestEnv()
            val tool =
                ReadNotificationsTool(FakeFeed(items = listOf(note())), AllPermissions, FakeClock(0L), env.prompts)

            val out = tool.invoke(JsonObject(emptyMap()), env.ctx())

            assertThat(out.ok).isTrue()
            val text = out.content.toString()
            assertThat(text).contains("<untrusted source=\"notification\">红包提醒</untrusted>")
            assertThat(text).contains("<untrusted source=\"notification\">点击领取</untrusted>")
            assertThat(text).contains("<untrusted source=\"notification\">在吗</untrusted>")
        }

    @Test
    fun `clipboard get wraps the text as untrusted`() =
        runTest {
            val env = ToolTestEnv()
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("t", "密码123"))
            val tool = ClipboardGetTool(context)

            val out = tool.invoke(JsonObject(emptyMap()), env.ctx())

            assertThat(out.ok).isTrue()
            assertThat(out.content.toString()).contains("<untrusted source=\"clipboard\">密码123</untrusted>")
        }

    private fun note(): NotificationInfo =
        NotificationInfo(
            key = "k1",
            packageName = "com.example.app",
            appLabel = "示例",
            postedAt = 0L,
            title = "红包提醒",
            text = "点击领取",
            bigText = null,
            subText = null,
            conversationTitle = null,
            isGroupConversation = false,
            messages = listOf(NotificationMessage(sender = "张三", text = "在吗", time = 0L)),
            category = null,
            isOngoing = false,
        )

    private class FakeFeed(
        private val items: List<NotificationInfo> = emptyList(),
    ) : NotificationFeed {
        override val posted: SharedFlow<NotificationInfo> = MutableSharedFlow()
        override val connected: MutableStateFlow<Boolean> = MutableStateFlow(true)

        override suspend fun active(packageName: String?): List<NotificationInfo> = items

        override suspend fun recent(
            packageName: String?,
            sinceMs: Long?,
            limit: Int,
        ): List<NotificationInfo> = items.take(limit)

        override fun requestRebind() = Unit
    }

    private object AllPermissions : PermissionChecks {
        override fun canDrawOverlay(): Boolean = true

        override fun canPostNotifications(): Boolean = true

        override fun canReadNotifications(): Boolean = true

        override fun canUseFullScreenIntent(): Boolean = true

        override fun isIgnoringBatteryOptimizations(): Boolean = true

        override fun canScheduleExactAlarms(): Boolean = true
    }
}
