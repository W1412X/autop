/**
 * 窗口脱敏决策（docs/tech/05 §3.2 步骤 1 的 `redaction` 参数）：
 * WP5 起由策略引擎驱动，把屏蔽应用的窗口置为 HIDE_WINDOW。
 */
package com.example.autop.device.impl

import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.core.RedactionMode
import com.example.autop.policy.PolicyEngine
import javax.inject.Inject
import javax.inject.Singleton

/** 窗口脱敏决策端口。 */
public interface WindowRedactor {
    /** 对窗口的脱敏处理（屏蔽名单取自 [config]，WP7.2）。 */
    public fun redact(
        window: WindowSnapshot,
        config: ConfigSnapshot,
    ): RedactionMode
}

/** 策略引擎驱动的实现（docs/tech/06 §5 `PolicyEngine.redaction`）：屏蔽应用的窗口整体隐藏。 */
@Singleton
public class PolicyWindowRedactor
    @Inject
    constructor(
        private val policy: PolicyEngine,
    ) : WindowRedactor {
        override fun redact(
            window: WindowSnapshot,
            config: ConfigSnapshot,
        ): RedactionMode =
            if (policy.redaction(window.packageName, config)) RedactionMode.HIDE_WINDOW else RedactionMode.NONE
    }
