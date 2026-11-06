/**
 * 自家界面交互窗口：操作悬浮球、录制面板、拾取层之后，目标应用会随后补发窗口切换事件，
 * 那不是用户在目标应用里的操作。记下最近一次自家交互的时间，窗口内的这类事件不计入录制。
 */
package com.example.autop.device.impl.record

/** 最近一次自家界面交互的时间窗口；线程安全。 */
internal class OwnInteractionWindow {
    @Volatile
    private var lastAt: Long = NEVER

    /** 记下自家界面交互发生在 [time]（事件时间戳）。 */
    fun note(time: Long) {
        lastAt = time
    }

    /** [time] 是否落在最近一次交互之后 [suppressMs] 毫秒内。 */
    fun covers(
        time: Long,
        suppressMs: Long,
    ): Boolean {
        val last = lastAt
        return last != NEVER && time - last in 0..suppressMs
    }

    private companion object {
        const val NEVER: Long = Long.MIN_VALUE
    }
}
