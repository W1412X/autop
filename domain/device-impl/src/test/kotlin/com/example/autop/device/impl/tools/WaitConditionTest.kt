package com.example.autop.device.impl.tools

import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Outcome
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * wait until 条件判定（N194）：`package_is` 的前台信息不依赖节点树，用降级快照判定——
 * 微信等应用屏蔽无障碍节点（空树）时不再永远判定未满足直到超时；
 * text 条件依赖节点文本，用完整快照判定（采集失败不判满足）。
 */
class WaitConditionTest {
    @Test
    fun `package_is judges by degraded snapshot when full snapshot fails`() =
        runTest {
            val satisfied =
                conditionSatisfied(
                    until = untilOf("""{"package_is":"com.tencent.mm"}"""),
                    snapshot =
                        snaps(
                            full = Outcome.Err(AppError(code = "device.service_unavailable", message = "空树")),
                            degraded = Outcome.Ok(screenOf(foreground = "com.tencent.mm")),
                        ),
                )

            assertThat(satisfied).isTrue()
        }

    @Test
    fun `package_is unmatched stays unsatisfied`() =
        runTest {
            val satisfied =
                conditionSatisfied(
                    until = untilOf("""{"package_is":"com.tencent.mm"}"""),
                    snapshot =
                        snaps(
                            full = Outcome.Err(AppError(code = "device.service_unavailable", message = "空树")),
                            degraded = Outcome.Ok(screenOf(foreground = "com.android.launcher")),
                        ),
                )

            assertThat(satisfied).isFalse()
        }

    @Test
    fun `text_appears requires full snapshot nodes`() =
        runTest {
            val satisfied =
                conditionSatisfied(
                    until = untilOf("""{"text_appears":"龙梦哥"}"""),
                    snapshot =
                        snaps(
                            full = Outcome.Err(AppError(code = "device.service_unavailable", message = "空树")),
                            degraded = Outcome.Ok(screenOf(foreground = "com.tencent.mm")),
                        ),
                )

            assertThat(satisfied).isFalse()
        }

    @Test
    fun `text_disappears satisfied when full snapshot lacks the text`() =
        runTest {
            val satisfied =
                conditionSatisfied(
                    until = untilOf("""{"text_disappears":"加载中"}"""),
                    snapshot =
                        snaps(
                            full = Outcome.Ok(screenOf(foreground = "com.tencent.mm", nodes = listOf(leaf("龙梦哥")))),
                            degraded = Outcome.Ok(screenOf(foreground = "com.tencent.mm")),
                        ),
                )

            assertThat(satisfied).isTrue()
        }

    @Test
    fun `mixed conditions all must hold`() =
        runTest {
            val satisfied =
                conditionSatisfied(
                    until = untilOf("""{"package_is":"com.tencent.mm","text_appears":"龙梦哥"}"""),
                    snapshot =
                        snaps(
                            full = Outcome.Ok(screenOf(foreground = "com.tencent.mm", nodes = listOf(leaf("龙龙哥")))),
                            degraded = Outcome.Ok(screenOf(foreground = "com.tencent.mm")),
                        ),
                )

            assertThat(satisfied).isFalse()
        }

    @Test
    fun `awaitCondition gives up after deadline without satisfaction`() =
        runTest {
            val satisfied =
                awaitCondition(
                    until = untilOf("""{"package_is":"com.tencent.mm"}"""),
                    deadline = 5_000L,
                    pollMs = 0L,
                    clock = TickingClock(),
                    snapshot =
                        snaps(
                            full = Outcome.Err(AppError(code = "device.service_unavailable", message = "空树")),
                            degraded = Outcome.Ok(screenOf(foreground = "com.android.launcher")),
                        ),
                )

            assertThat(satisfied).isFalse()
        }

    @Test
    fun `awaitCondition returns true as soon as condition holds`() =
        runTest {
            val satisfied =
                awaitCondition(
                    until = untilOf("""{"package_is":"com.tencent.mm"}"""),
                    deadline = 5_000L,
                    pollMs = 0L,
                    clock = TickingClock(),
                    snapshot =
                        snaps(
                            full = Outcome.Err(AppError(code = "device.service_unavailable", message = "空树")),
                            degraded = Outcome.Ok(screenOf(foreground = "com.tencent.mm")),
                        ),
                )

            assertThat(satisfied).isTrue()
        }

    /** 快照来源：`degraded = true` 给降级快照，否则给完整快照。 */
    private fun snaps(
        full: Outcome<ScreenSnapshot>,
        degraded: Outcome<ScreenSnapshot>,
    ): suspend (Boolean) -> Outcome<ScreenSnapshot> = { d -> if (d) degraded else full }

    private fun untilOf(json: String): JsonObject = AutopJson.default.parseToJsonElement(json) as JsonObject

    private fun screenOf(
        foreground: String,
        nodes: List<NodeSnapshot> = emptyList(),
    ): ScreenSnapshot =
        ScreenSnapshot(
            capturedAt = 1_000_000L,
            windows = emptyList(),
            nodes = nodes,
            foregroundPackage = foreground,
            activity = null,
            screenWidth = 1080,
            screenHeight = 2400,
            rotation = 0,
            keyguardLocked = false,
            truncated = false,
        )

    private fun leaf(text: String): NodeSnapshot =
        NodeSnapshot(
            idx = 0,
            children = emptyList(),
            childIndex = 0,
            text = text,
            bounds = Rect(0, 0, 1080, 2400),
            flags = NodeFlags.VISIBLE,
        )
}

/** 每次读取前进 1 秒的时钟：让等待循环能越过 deadline（runTest 的 delay 是虚拟即时）。 */
private class TickingClock : Clock {
    private var now = 0L

    override fun nowMillis(): Long = now.also { now += 1_000L }
}
