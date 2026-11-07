/**
 * [com.example.autop.device.ScreenReader] 实现（docs/tech/05 §1.3）：无障碍快照 + 截图。
 * visual/llm 观测不经过这里（docs/design/observation-modes-coordinate-actions §2.2），
 * 由 ScreenObserver 按显式模式编排。
 */
package com.example.autop.device.impl

import com.example.autop.core.common.Outcome
import com.example.autop.device.DeviceErrorCodes
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

        /**
         * 尽力快照（N193）：只在“应用窗口空树/采集超时”这类不依赖服务连接的问题上降级为无节点快照，
         * 坐标手势不被空树卡死；服务未连接等错误向上传播，避免把不可用说成“空树”。
         */
        override suspend fun snapshotOrDegraded(): Outcome<ScreenSnapshot> =
            when (val got = capturer.capture()) {
                is Outcome.Ok -> got
                is Outcome.Err ->
                    if (got.error.code == DeviceErrorCodes.APP_TREE_EMPTY ||
                        got.error.code == DeviceErrorCodes.TIMEOUT
                    ) {
                        Outcome.Ok(capturer.buildSnapshot())
                    } else {
                        got
                    }
            }

        override suspend fun screenshot(): Outcome<Screenshot> = screenshotTaker.take()
    }
