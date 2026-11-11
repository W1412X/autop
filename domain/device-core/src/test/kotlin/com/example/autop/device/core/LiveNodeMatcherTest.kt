/**
 * 实时节点匹配（coordinate-node-action-split §3.3）：唯一命中、多候选按窗口与路径消歧、
 * 无匹配与 bounds 变化判失效。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveNodeMatcherTest {
    private val box = Rect(100, 100, 200, 150)

    @Test
    fun `unique candidate is returned`() {
        val live = snapshotOf(listOf(leaf(idx = 2, childIndex = 0)))
        val recorded = NodeTargets.of(live, leafOf(live, 2))

        val target = LiveNodeMatcher.find(live, recorded)

        assertThat(target?.node?.idx).isEqualTo(2)
        assertThat(target?.windowId).isEqualTo(7)
        assertThat(target?.path).containsExactly(0).inOrder()
    }

    @Test
    fun `duplicate candidates are disambiguated by path`() {
        val live =
            snapshotOf(
                listOf(
                    leaf(idx = 2, childIndex = 0),
                    leaf(idx = 3, childIndex = 1),
                ),
            )
        // 记录的是第二个（子序号 1）；实时树里两个候选几何/语义完全相同。
        val recorded = NodeTargets.of(live, leafOf(live, 3))

        val target = LiveNodeMatcher.find(live, recorded)

        assertThat(target?.node?.idx).isEqualTo(3)
        assertThat(target?.path).containsExactly(1).inOrder()
    }

    @Test
    fun `no candidate returns null`() {
        val recorded = NodeTargets.of(snapshotOf(listOf(leaf(idx = 2, childIndex = 0))), leaf(idx = 2, childIndex = 0))
        val empty = snapshotOf(emptyList())

        assertThat(LiveNodeMatcher.find(empty, recorded)).isNull()
    }

    @Test
    fun `bounds change is stale`() {
        val recordedSnapshot = snapshotOf(listOf(leaf(idx = 2, childIndex = 0)))
        val recorded = NodeTargets.of(recordedSnapshot, leafOf(recordedSnapshot, 2))
        val moved = snapshotOf(listOf(leaf(idx = 2, childIndex = 0, bounds = Rect(100, 300, 200, 350))))

        assertThat(LiveNodeMatcher.find(moved, recorded)).isNull()
    }

    @Test
    fun `package mismatch is stale`() {
        val live = snapshotOf(listOf(leaf(idx = 2, childIndex = 0)))
        val recorded = NodeTargets.of(live, leafOf(live, 2))
        val other = recorded.copy(node = recorded.node.copy(packageName = "com.other.app"))

        assertThat(LiveNodeMatcher.find(live, other)).isNull()
    }

    private fun leafOf(
        snapshot: ScreenSnapshot,
        idx: Int,
    ): NodeSnapshot = snapshot.nodes.single { it.idx == idx }

    private fun leaf(
        idx: Int,
        childIndex: Int,
        bounds: Rect = box,
    ): NodeSnapshot =
        NodeSnapshot(
            idx = idx,
            parent = 1,
            children = emptyList(),
            childIndex = childIndex,
            className = "android.widget.Button",
            text = "确定",
            bounds = bounds,
            flags = 0,
            packageName = "com.example.app",
        )

    private fun snapshotOf(leaves: List<NodeSnapshot>): ScreenSnapshot {
        val root =
            NodeSnapshot(
                idx = 1,
                parent = null,
                children = leaves.map { it.idx },
                childIndex = 0,
                className = "android.widget.FrameLayout",
                bounds = Rect(0, 0, 1080, 1920),
                flags = 0,
                packageName = "com.example.app",
            )
        val window =
            WindowSnapshot(
                windowId = 7,
                type = WindowType.APPLICATION,
                layer = 0,
                packageName = "com.example.app",
                title = null,
                bounds = Rect(0, 0, 1080, 1920),
                active = true,
                focused = true,
                rootIdx = 1,
            )
        return ScreenSnapshot(
            capturedAt = 0L,
            windows = listOf(window),
            nodes = listOf(root) + leaves,
            foregroundPackage = "com.example.app",
            activity = "com.example.app.MainActivity",
            screenWidth = 1080,
            screenHeight = 1920,
            rotation = 0,
            keyguardLocked = false,
            truncated = false,
        )
    }
}
