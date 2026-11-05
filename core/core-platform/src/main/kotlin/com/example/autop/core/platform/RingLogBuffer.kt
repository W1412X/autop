package com.example.autop.core.platform

/**
 * 环形日志缓冲（docs/tech/02 §4.4）：只保留最近 [capacity] 条，
 * 供"设置 → 高级 → 诊断日志"页查看与复制。容量由配置（`ui.debug.logBufferSize`）驱动，可运行期调整。
 */
internal class RingLogBuffer(
    capacity: Int,
) {
    private val entries = ArrayDeque<String>()

    /** 当前容量；调小会立即裁掉最旧的条目。 */
    var capacity: Int = capacity
        set(value) {
            field = value.coerceAtLeast(MIN_CAPACITY)
            synchronized(entries) { trim() }
        }

    /** 追加一条（最旧在前）；超容量时丢弃最旧的。 */
    fun append(line: String) {
        synchronized(entries) {
            entries.addLast(line)
            trim()
        }
    }

    /** 当前全部条目，最旧在前。 */
    fun snapshot(): List<String> = synchronized(entries) { entries.toList() }

    private fun trim() {
        while (entries.size > capacity) entries.removeFirst()
    }

    private companion object {
        const val MIN_CAPACITY = 1
    }
}
