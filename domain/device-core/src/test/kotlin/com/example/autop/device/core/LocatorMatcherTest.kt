/**
 * 定位匹配单测（docs/tech/05 §3.5）：a–d 四层规则与消歧。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeLocator
import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocatorMatcherTest {
    private fun locator(block: LocatorSpec.() -> Unit): NodeLocator = LocatorSpec().apply(block).build()

    private class LocatorSpec {
        var shortId: String = "n1"
        var windowId: Int = 1
        var viewId: String? = null
        var text: String? = null
        var desc: String? = null
        var className: String? = null
        var path: List<Int> = emptyList()
        var bounds: Rect = Rect(0, 0, 50, 50)

        fun build() = NodeLocator(shortId, windowId, viewId, text, desc, className, path, bounds, "k")
    }

    private fun node(
        idx: Int,
        block: com.example.autop.device.core.NodeSpec.() -> Unit = {},
    ) = TestScreens.node(idx, block)

    @Test
    fun layerAMatchesViewIdAndLabel() {
        val target =
            node(2) {
                parent = 1
                viewId = "com.example:id/ok"
                text = "确定"
                className = "android.widget.Button"
            }
        val other =
            node(3) {
                parent = 1
                viewId = "com.example:id/ok"
                text = "取消"
                className = "android.widget.Button"
            }
        val screen = TestScreens.simple(target, other)
        val hit =
            LocatorMatcher.match(
                screen,
                locator {
                    viewId = "com.example:id/ok"
                    text = "确定"
                },
            )
        assertThat(hit?.idx).isEqualTo(2)
    }

    @Test
    fun layerBMatchesPathAndClassName() {
        val target =
            node(2) {
                parent = 1
                childIndex = 0
                className = "android.widget.Button"
                text = "旧文案"
            }
        val screen = TestScreens.simple(target)
        val hit =
            LocatorMatcher.match(
                screen,
                locator {
                    className = "android.widget.Button"
                    text = "新文案"
                    path = listOf(0)
                },
            )
        assertThat(hit?.idx).isEqualTo(2)
    }

    @Test
    fun layerCMatchesLabelAndClassName() {
        val target =
            node(2) {
                parent = 1
                className = "android.widget.TextView"
                text = "标题"
            }
        val screen = TestScreens.simple(target)
        val hit =
            LocatorMatcher.match(
                screen,
                locator {
                    className = "android.widget.TextView"
                    desc = "标题"
                },
            )
        assertThat(hit?.idx).isEqualTo(2)
    }

    @Test
    fun layerDFallsBackToViewIdOnly() {
        val target =
            node(2) {
                parent = 1
                viewId = "com.example:id/icon"
                className = "android.widget.ImageView"
                contentDesc = "旧图标"
            }
        val screen = TestScreens.simple(target)
        val hit =
            LocatorMatcher.match(
                screen,
                locator {
                    viewId = "com.example:id/icon"
                    desc = "新图标"
                    className = "android.widget.ImageView"
                },
            )
        assertThat(hit?.idx).isEqualTo(2)
    }

    @Test
    fun multipleCandidatesTakeNearestCenter() {
        val near =
            node(2) {
                parent = 1
                viewId = "com.example:id/item"
                text = "条目"
                bounds = Rect(0, 0, 100, 100)
            }
        val far =
            node(3) {
                parent = 1
                viewId = "com.example:id/item"
                text = "条目"
                bounds = Rect(900, 900, 1000, 1000)
            }
        val screen = TestScreens.simple(near, far)
        val hit =
            LocatorMatcher.match(
                screen,
                locator {
                    viewId = "com.example:id/item"
                    text = "条目"
                    bounds = Rect(10, 10, 90, 90)
                },
            )
        assertThat(hit?.idx).isEqualTo(2)
    }

    @Test
    fun noCandidatesReturnsNull() {
        val screen =
            TestScreens.simple(
                node(2) {
                    parent = 1
                    className = "android.widget.Button"
                    text = "存在的"
                    flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE
                },
            )
        val hit =
            LocatorMatcher.match(
                screen,
                locator {
                    viewId = "com.example:id/missing"
                    text = "不存在"
                    className = "android.widget.Button"
                    path = listOf(9, 9)
                },
            )
        assertThat(hit).isNull()
    }
}
