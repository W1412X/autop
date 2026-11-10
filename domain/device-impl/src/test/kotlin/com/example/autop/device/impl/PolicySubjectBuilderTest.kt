package com.example.autop.device.impl

import com.example.autop.core.common.AutopJson
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** 策略主体生成（docs/tech/05 §6）：目标文字、屏幕文字、密码与安全窗口标记。 */
class PolicySubjectBuilderTest {
    private val clock = FakeClock(start = 1_000_000L)
    private val cache = SecureWindowCache(clock)

    @Test
    fun `target text merges text desc and visible descendant labels`() {
        val provider = FakeConfigProvider()
        val builder = PolicySubjectBuilder(cache)
        val screen =
            screenOf(
                listOf(container(0, listOf(1, 2), text = "标题"), leaf(1, text = "确定"), leaf(2, text = "取消")),
            )
        val subject = builder.build("tool.tap", screen, screen.nodes[0], emptyMap(), provider.current())
        assertThat(subject.targetText).isEqualTo("标题 · 确定 · 取消")
        assertThat(subject.packageName).isEqualTo("com.example.app")
        assertThat(subject.targetIsPassword).isFalse()
    }

    @Test
    fun `password and data sensitive flags surface on the subject`() {
        val provider = FakeConfigProvider()
        val builder = PolicySubjectBuilder(cache)
        val flags = NodeFlags.VISIBLE or NodeFlags.PASSWORD or NodeFlags.DATA_SENSITIVE
        val screen = screenOf(listOf(leaf(0, text = "密码", flags = flags)))
        val subject = builder.build("tool.tap", screen, screen.nodes[0], mapOf("k" to "v"), provider.current())
        assertThat(subject.targetIsPassword).isTrue()
        assertThat(subject.targetDataSensitive).isTrue()
        assertThat(subject.hasPasswordField).isTrue()
        assertThat(subject.extra).containsExactly("k", "v")
    }

    @Test
    fun `secure window cache expires after configured time`() {
        val provider = FakeConfigProvider()
        val builder = PolicySubjectBuilder(cache)
        val screen = screenOf(listOf(leaf(0, text = "确定")))
        cache.markSecure()
        assertThat(builder.build("tool.tap", screen, null, emptyMap(), provider.current()).windowSecure).isTrue()
        clock.advanceBy(5_001L)
        assertThat(builder.build("tool.tap", screen, null, emptyMap(), provider.current()).windowSecure).isFalse()
    }

    @Test
    fun `screen texts respect configured cap and skip invisible labels`() {
        val overrides = overlay("""{"policy":{"subject":{"maxScreenTexts":2}}}""")
        val provider = FakeConfigProvider(overrides)
        val builder = PolicySubjectBuilder(cache)
        val invisible = leaf(1, text = "隐藏", flags = 0)
        val screen =
            screenOf(listOf(leaf(0, text = "甲"), invisible, leaf(2, text = "乙"), leaf(3, text = "丙")))
        val subject = builder.build("tool.tap", screen, null, emptyMap(), provider.current())
        assertThat(subject.screenTexts).containsExactly("甲", "乙").inOrder()
    }

    private fun overlay(text: String): JsonObject = AutopJson.default.parseToJsonElement(text) as JsonObject

    private fun leaf(
        idx: Int,
        childIndex: Int = 0,
        text: String? = null,
        flags: Int = NodeFlags.VISIBLE,
    ): NodeSnapshot =
        NodeSnapshot(
            idx = idx,
            children = emptyList(),
            childIndex = childIndex,
            text = text,
            bounds = Rect(0, 0, 100, 40),
            flags = flags,
        )

    private fun container(
        idx: Int,
        children: List<Int>,
        text: String? = null,
        flags: Int = NodeFlags.VISIBLE,
    ): NodeSnapshot =
        NodeSnapshot(
            idx = idx,
            children = children,
            childIndex = 0,
            text = text,
            bounds = Rect(0, 0, 100, 200),
            flags = flags,
        )

    private fun screenOf(nodes: List<NodeSnapshot>): ScreenSnapshot =
        ScreenSnapshot(
            capturedAt = clock.nowMillis(),
            windows = emptyList(),
            nodes = nodes,
            foregroundPackage = "com.example.app",
            activity = "com.example.app.MainActivity",
            screenWidth = 1080,
            screenHeight = 2400,
            rotation = 0,
            keyguardLocked = false,
            truncated = false,
        )
}
