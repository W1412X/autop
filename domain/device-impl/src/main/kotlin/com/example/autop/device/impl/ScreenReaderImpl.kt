/**
 * [com.example.autop.device.ScreenReader] 实现（docs/tech/05 §1.3）：无障碍快照 + 截图。
 * visual/llm 观测不经过这里（docs/design/observation-modes-coordinate-actions §2.2），
 * 由 ScreenObserver 按显式模式编排。
 */
package com.example.autop.device.impl

import com.example.autop.core.common.Outcome
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.Screenshot
import javax.inject.Inject
import javax.inject.Singleton

/** 快照与截图入口。 */
@Singleton
public class ScreenReaderImpl
    @Inject
    constructor(
        private val capturer: SnapshotCapturer,
        private val screenshotTaker: ScreenshotTaker,
    ) : ScreenReader {
        override suspend fun snapshot(): Outcome<ScreenSnapshot> = capturer.capture()

        /** 尽力快照（N193）：采集失败降级为无节点快照，坐标手势不被空树卡死。 */
        override suspend fun snapshotOrDegraded(): Outcome<ScreenSnapshot> =
            when (val got = capturer.capture()) {
                is Outcome.Ok -> got
                is Outcome.Err -> Outcome.Ok(capturer.buildSnapshot())
            }

        override suspend fun screenshot(): Outcome<Screenshot> = screenshotTaker.take()
    }
