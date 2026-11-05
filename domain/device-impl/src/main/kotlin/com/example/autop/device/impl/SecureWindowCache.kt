/**
 * 安全窗口缓存（docs/tech/05 §6）：最近一次截图是否返回 secure 错误，
 * 供 [PolicySubjectBuilder] 填 `windowSecure`。
 */
package com.example.autop.device.impl

import com.example.autop.core.common.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** 记录最近一次 `device.screenshot_secure` 的时间。 */
@Singleton
public class SecureWindowCache
    @Inject
    constructor(
        private val clock: Clock,
    ) {
        private var lastSecureAt: Long = 0

        /** 截图返回安全窗口错误时标记。 */
        public fun markSecure() {
            lastSecureAt = clock.nowMillis()
        }

        /** 缓存 [cacheMs] 内视为当前处于安全窗口。 */
        public fun isSecure(cacheMs: Long): Boolean {
            val last = lastSecureAt
            return last > 0 && clock.nowMillis() - last <= cacheMs
        }
    }
