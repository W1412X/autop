/**
 * input_text（docs/design/tool_optimize/02 §3、06 §1.3）：只认 FOCUSED+EDITABLE 焦点；
 * 无焦点/焦点不可编辑/密码框失败并附执行记录；SET_TEXT 被拒时按 shell 连接状态
 * 做剪贴板粘贴回退；replace 空串清空、append 空串拒绝。
 */
package com.example.autop.device.impl.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autop.agent.PreparedAction
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.Rect
import com.example.autop.device.ShellState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class InputTextToolTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `click then set text writes to the focused editable`() =
        runTest {
            val env = ToolTestEnv()
            val shell = FakeShell()
            env.gestures.focused = FakeResolvedNode(editableNode(1))
            env.queue(a11yScreenOf(listOf(clickableNode(1, "输入框", Rect(300, 100, 700, 200)))))
            val tool = env.inputTextTool(shell)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"x":500,"y":150,"text":"你好"}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            assertThat(env.gestures.taps).hasSize(1)
            val setText = env.gestures.nodeActionCalls.single { it.first == ACTION_SET_TEXT }
            assertThat(setText.second.values.single()).isEqualTo("你好")
            val trace = out.trace.joinToString("\n")
            assertThat(trace).contains("已点击输入框")
            assertThat(trace).contains("ACTION_SET_TEXT")
            assertThat(out.content.toString()).contains("已输入")
        }

    @Test
    fun `click without focus fails and keeps the clicked line`() =
        runTest {
            val env = ToolTestEnv()
            val shell = FakeShell()
            env.gestures.focused = null
            env.queue(a11yScreenOf(listOf(clickableNode(1, "输入框", Rect(300, 100, 700, 200)))))
            val tool = env.inputTextTool(shell)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"x":500,"y":150,"text":"你好"}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(DeviceErrorCodes.NOT_EDITABLE)
            assertThat(out.trace.joinToString("\n")).contains("已点击输入框")
            assertThat(env.gestures.nodeActions).isEmpty()
        }

    @Test
    fun `focused but not editable fails without sending keys`() =
        runTest {
            val env = ToolTestEnv()
            val shell = FakeShell()
            env.gestures.focused = FakeResolvedNode(editableNode(1, editable = false))
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.inputTextTool(shell)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"text":"你好"}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(DeviceErrorCodes.NOT_EDITABLE)
            assertThat(out.content.toString()).contains("当前焦点不是输入框")
            assertThat(env.gestures.nodeActions).isEmpty()
            assertThat(env.gestures.shellKeys).isEmpty()
        }

    @Test
    fun `password field fails without writing`() =
        runTest {
            val env = ToolTestEnv()
            val shell = FakeShell()
            env.gestures.focused = FakeResolvedNode(editableNode(1, password = true))
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.inputTextTool(shell)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"text":"secret"}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(DeviceErrorCodes.PASSWORD_FIELD)
            assertThat(env.gestures.nodeActions).isEmpty()
        }

    @Test
    fun `set text rejected falls back to shell paste and restores clipboard`() =
        runTest {
            val env = ToolTestEnv()
            val shell = FakeShell()
            env.gestures.nodeActionOk = false
            env.gestures.focused = FakeResolvedNode(editableNode(1))
            clipboard().setPrimaryClip(ClipData.newPlainText("old", "旧内容"))
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.inputTextTool(shell)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"text":"你好"}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            val trace = out.trace.joinToString("\n")
            assertThat(trace).contains("SET_TEXT 被应用拒绝，改用 shell 粘贴")
            assertThat(trace).contains("剪贴板：已恢复动作前的内容")
            assertThat(clipboardText()).isEqualTo("旧内容")
            assertThat(env.gestures.shellKeys).contains(KEYCODE_PASTE)
        }

    @Test
    fun `set text rejected without shell fails with the pairing hint`() =
        runTest {
            val env = ToolTestEnv()
            val shell = FakeShell()
            shell.state.value = ShellState.Disconnected
            env.gestures.nodeActionOk = false
            env.gestures.focused = FakeResolvedNode(editableNode(1))
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.inputTextTool(shell)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"text":"你好"}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(DeviceErrorCodes.ACTION_FAILED)
            assertThat(out.trace.joinToString("\n")).contains("SET_TEXT 被应用拒绝")
            assertThat(out.content.toString()).contains("无线调试")
        }

    @Test
    fun `replace empty string clears via set text`() =
        runTest {
            val env = ToolTestEnv()
            val shell = FakeShell()
            env.gestures.focused = FakeResolvedNode(editableNode(1))
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.inputTextTool(shell)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"text":""}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            val setText = env.gestures.nodeActionCalls.single { it.first == ACTION_SET_TEXT }
            assertThat(setText.second.values.single()).isEqualTo("")
            assertThat(out.content.toString()).contains("已清空输入框")
        }

    @Test
    fun `append with empty text is rejected`() =
        runTest {
            val env = ToolTestEnv()
            val tool = env.inputTextTool(FakeShell())
            val ctx = env.ctx()

            val got = tool.prepare(args("""{"text":"","mode":"append"}"""), ctx)

            val error = (got as Outcome.Err).error
            assertThat(error.code).isEqualTo(CommonErrorCodes.INVALID_ARGUMENT)
            assertThat(error.message).contains("append")
        }

    @Test
    fun `clearing via shell does not touch the clipboard`() =
        runTest {
            val env = ToolTestEnv()
            val shell = FakeShell()
            env.gestures.nodeActionOk = false
            env.gestures.focused = FakeResolvedNode(editableNode(1))
            clipboard().setPrimaryClip(ClipData.newPlainText("old", "旧内容"))
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.inputTextTool(shell)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"text":""}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            assertThat(clipboardText()).isEqualTo("旧内容")
            val trace = out.trace.joinToString("\n")
            assertThat(trace).contains("全选删除")
            assertThat(trace).contains("剪贴板：未改动")
            assertThat(env.gestures.shellKeys).doesNotContain(KEYCODE_PASTE)
        }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun okOf(outcome: Outcome<PreparedAction>): PreparedAction =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected ok, was ${outcome.error.code}")
        }

    private fun clipboard(): ClipboardManager = context.getSystemService(ClipboardManager::class.java)

    private fun clipboardText(): String? =
        clipboard()
            .primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()

    private companion object {
        const val KEYCODE_PASTE: Int = 279
        val ACTION_SET_TEXT: Int = AccessibilityNodeInfo.ACTION_SET_TEXT
    }
}
