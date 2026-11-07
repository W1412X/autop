package com.example.autop.device.impl

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 空树重试（docs/tech/05 §2.3、§11，R1/A3）：判定、重试恢复、重试耗尽与错误透传。 */
class CaptureRetryTest {
    @Test
    fun `empty app tree means no root or root without children`() {
        assertThat(hasEmptyAppTree(screen(appWindow(rootIdx = null), emptyList()))).isTrue()
        assertThat(hasEmptyAppTree(screen(appWindow(rootIdx = 0), listOf(node(0, emptyList()))))).isTrue()
        val hasChildren = screen(appWindow(rootIdx = 0), listOf(node(0, listOf(1)), node(1, emptyList())))
        assertThat(hasEmptyAppTree(hasChildren)).isFalse()
    }

    @Test
    fun `windows without application window are never empty-tree`() {
        val systemOnly = screen(systemWindow(rootIdx = null), emptyList())
        assertThat(hasEmptyAppTree(systemOnly)).isFalse()
    }

    @Test
    fun `transient empty tree recovers on retry`() =
        runTest {
            var calls = 0
            val good = screen(appWindow(rootIdx = 0), listOf(node(0, listOf(1)), node(1, emptyList())))
            val got =
                captureWithEmptyTreeRetry(
                    retries = 3,
                    delayMs = 10,
                    emptyTree = ::hasEmptyAppTree,
                    capture = {
                        calls++
                        Outcome.Ok(if (calls == 1) screen(appWindow(rootIdx = null), emptyList()) else good)
                    },
                    exhausted = { Outcome.Err(unavailable()) },
                )
            assertThat(calls).isEqualTo(2)
            assertThat((got as Outcome.Ok).value).isEqualTo(good)
        }

    @Test
    fun `still empty after retries maps to app tree empty`() =
        runTest {
            var calls = 0
            val got =
                captureWithEmptyTreeRetry(
                    retries = 3,
                    delayMs = 10,
                    emptyTree = ::hasEmptyAppTree,
                    capture = {
                        calls++
                        Outcome.Ok(screen(appWindow(rootIdx = null), emptyList()))
                    },
                    exhausted = { Outcome.Err(unavailable()) },
                )
            // 首次采集 + 3 次重试
            assertThat(calls).isEqualTo(4)
            assertThat((got as Outcome.Err).error.code).isEqualTo("device.app_tree_empty")
        }

    @Test
    fun `non-empty result returns without retry`() =
        runTest {
            var calls = 0
            val good = screen(appWindow(rootIdx = 0), listOf(node(0, listOf(1)), node(1, emptyList())))
            val got =
                captureWithEmptyTreeRetry(
                    retries = 3,
                    delayMs = 10,
                    emptyTree = ::hasEmptyAppTree,
                    capture = {
                        calls++
                        Outcome.Ok(good)
                    },
                    exhausted = { Outcome.Err(unavailable()) },
                )
            assertThat(calls).isEqualTo(1)
            assertThat(got).isInstanceOf(Outcome.Ok::class.java)
        }

    @Test
    fun `capture errors pass through without retry`() =
        runTest {
            var calls = 0
            val got =
                captureWithEmptyTreeRetry<ScreenSnapshot>(
                    retries = 3,
                    delayMs = 10,
                    emptyTree = ::hasEmptyAppTree,
                    capture = {
                        calls++
                        Outcome.Err(AppError(code = "device.timeout", message = "boom"))
                    },
                    exhausted = { Outcome.Err(unavailable()) },
                )
            assertThat(calls).isEqualTo(1)
            assertThat((got as Outcome.Err).error.code).isEqualTo("device.timeout")
        }

    private fun unavailable(): AppError = AppError(code = "device.app_tree_empty", message = "empty tree")

    private fun screen(
        window: WindowSnapshot,
        nodes: List<NodeSnapshot>,
    ): ScreenSnapshot =
        ScreenSnapshot(
            capturedAt = 0L,
            windows = listOf(window),
            nodes = nodes,
            screenWidth = 1080,
            screenHeight = 2400,
            rotation = 0,
            keyguardLocked = false,
            truncated = false,
        )

    private fun appWindow(rootIdx: Int?): WindowSnapshot = window(WindowType.APPLICATION, rootIdx)

    private fun systemWindow(rootIdx: Int?): WindowSnapshot = window(WindowType.SYSTEM, rootIdx)

    private fun window(
        type: WindowType,
        rootIdx: Int?,
    ): WindowSnapshot =
        WindowSnapshot(
            windowId = 1,
            type = type,
            layer = 0,
            packageName = "com.example.app",
            title = null,
            bounds = Rect(0, 0, 1080, 2400),
            active = true,
            focused = true,
            rootIdx = rootIdx,
        )

    private fun node(
        idx: Int,
        children: List<Int>,
    ): NodeSnapshot =
        NodeSnapshot(
            idx = idx,
            children = children,
            childIndex = 0,
            bounds = Rect(0, 0, 100, 100),
            flags = 0,
        )
}
