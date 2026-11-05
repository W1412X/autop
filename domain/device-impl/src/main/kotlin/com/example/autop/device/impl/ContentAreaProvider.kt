/**
 * 最近一次快照的内容区缓存（N204）：截图链（screenshot 工具、动作后截图）不重新采集
 * 快照，取这里最近一次采集/降级外的真实内容区；从未采集过时为 null（视作全屏）。
 */
package com.example.autop.device.impl

import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.Rect
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** 内容区缓存（采集侧写、截图侧读）。 */
@Singleton
public class ContentAreaProvider
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
    ) {
        private val latest: AtomicReference<Rect?> = AtomicReference(null)

        /** 采集到真实窗口数据时更新；降级快照（无窗口）不覆盖上次的值。 */
        public fun update(area: Rect) {
            latest.set(area)
        }

        /** 最近内容区（像素）；null 表示尚无采集数据，调用方视作全屏。 */
        public fun current(): Rect? = latest.get()

        /**
         * 生效的坐标基准区（N204）：`hideSystemDecoration` 开（默认）时为最近内容区，
         * 关时为 null（全屏，图与树都保持旧行为）。
         */
        public fun base(): Rect? {
            val hide = configProvider.current()[DeviceConfig.SERIALIZER].hideSystemDecoration
            return if (hide) latest.get() else null
        }
    }
