package com.example.autop.core.testing

import com.example.autop.core.common.Clock

/** 可控时钟（docs/tech/02 §5）：测试推进时间而不依赖真实时间（任务书测试要求）。 */
public class FakeClock(
    start: Long = 0L,
) : Clock {
    private var nowMs: Long = start

    override fun nowMillis(): Long = nowMs

    /** 推进 [ms] 毫秒。 */
    public fun advanceBy(ms: Long) {
        nowMs += ms
    }
}
