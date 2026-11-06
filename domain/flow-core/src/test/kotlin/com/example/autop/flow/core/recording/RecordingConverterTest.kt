package com.example.autop.flow.core.recording

import com.example.autop.device.Rect
import com.example.autop.device.UserAction
import com.example.autop.device.UserActionType
import com.example.autop.flow.ExpectKind
import com.example.autop.flow.ToolNode
import com.example.autop.flow.WaitNode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class RecordingConverterTest {
    private val pkg = "com.example.app"
    private val bounds = Rect(0, 0, 100, 100)

    private fun action(
        type: UserActionType,
        text: String? = null,
        target: Rect? = bounds,
        password: Boolean = false,
    ) = UserAction(
        type = type,
        packageName = pkg,
        className = null,
        viewId = null,
        text = text,
        contentDescription = null,
        bounds = target,
        isPassword = password,
        time = 0L,
    )

    private fun convert(vararg actions: UserAction) =
        RecordingConverter.convert(
            flowId = "f",
            name = "t",
            actions = actions.toList(),
            screen = ScreenSize(1000, 2000),
            options = RecordingOptions(settleMinMs = 0, settleMaxMs = 0, maxSteps = 50),
        )

    @Test
    fun `click becomes a tap with text target after opening the start app`() {
        val draft = convert(action(UserActionType.CLICK, text = "设置"))
        val steps = draft.definition.steps
        assertThat((steps[0] as ToolNode).tool).isEqualTo("open_app")
        assertThat(steps[1]).isInstanceOf(WaitNode::class.java)
        val tap = steps[2] as ToolNode
        assertThat(tap.tool).isEqualTo("tap")
        assertThat(tap.target?.value).isEqualTo("设置")
        assertThat(tap.anchor?.app).isEqualTo(pkg)
    }

    @Test
    fun `click without text falls back to a permille coordinate`() {
        val draft = convert(action(UserActionType.CLICK, target = Rect(400, 800, 600, 1200)))
        val tap = draft.definition.steps[2] as ToolNode
        assertThat(tap.target?.value).isNull()
        assertThat(tap.target?.fallback?.at).isEqualTo(listOf(500, 500))
    }

    @Test
    fun `consecutive typed text on the same field becomes one input step`() {
        val draft =
            convert(
                action(UserActionType.TEXT_CHANGED, text = "a"),
                action(UserActionType.TEXT_CHANGED, text = "ab"),
                action(UserActionType.TEXT_CHANGED, text = "abc"),
            )
        val input =
            draft.definition.steps
                .filterIsInstance<ToolNode>()
                .single { it.tool == "input_text" }
        assertThat((input.args["text"] as JsonPrimitive).content).isEqualTo("abc")
        assertThat(draft.typedNodes.values).containsExactly("abc")
    }

    @Test
    fun `password input is never recorded`() {
        val draft = convert(action(UserActionType.TEXT_CHANGED, text = null, password = true))
        assertThat(
            draft.definition.steps
                .filterIsInstance<ToolNode>()
                .map { it.tool },
        ).containsExactly("open_app")
        assertThat(draft.typedNodes).isEmpty()
    }

    @Test
    fun `a click followed by a window change expects the screen to change and waits`() {
        val draft = convert(action(UserActionType.CLICK, text = "进入"), action(UserActionType.WINDOW_CHANGED))
        val tap = draft.definition.steps[2] as ToolNode
        assertThat(tap.expect?.kind).isEqualTo(ExpectKind.SCREEN_CHANGED)
        assertThat(draft.definition.steps[3]).isInstanceOf(WaitNode::class.java)
        assertThat(draft.definition.steps).hasSize(4)
    }

    @Test
    fun `consecutive scrolls in one direction merge into a single step`() {
        val draft =
            convert(
                action(UserActionType.SCROLL_FORWARD),
                action(UserActionType.SCROLL_FORWARD),
                action(UserActionType.SCROLL_FORWARD),
                action(UserActionType.SCROLL_BACKWARD),
            )
        val scrolls =
            draft.definition.steps
                .filterIsInstance<ToolNode>()
                .filter { it.tool == "scroll" }
        assertThat(
            scrolls.map { (it.args["direction"] as JsonPrimitive).content },
        ).containsExactly("down", "up").inOrder()
    }

    @Test
    fun `a picked wait step becomes a wait for element with a target`() {
        val draft = convert(action(UserActionType.WAIT_ELEMENT, text = "完成"))
        val wait = draft.definition.steps.last() as WaitNode
        assertThat(wait.waitFor).isEqualTo(com.example.autop.flow.WaitFor.ELEMENT)
        assertThat(wait.target?.value).isEqualTo("完成")
    }

    @Test
    fun `promoting a typed node turns its text into a parameter with the recorded default`() {
        val draft = convert(action(UserActionType.TEXT_CHANGED, text = "abc"))
        val id = draft.typedNodes.keys.single()
        val promoted = RecordingConverter.promoteParams(draft.definition, mapOf(id to "keyword"))
        val input = promoted.steps.filterIsInstance<ToolNode>().single { it.id == id }
        assertThat((input.args["text"] as JsonPrimitive).content).isEqualTo("{{keyword}}")
        assertThat(promoted.params.single().name).isEqualTo("keyword")
        assertThat((promoted.params.single().default as JsonPrimitive).content).isEqualTo("abc")
    }
}
