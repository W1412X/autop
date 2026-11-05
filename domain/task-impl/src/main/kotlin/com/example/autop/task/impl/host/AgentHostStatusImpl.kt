/**
 * [com.example.autop.task.AgentHostStatus] 的实现（WP11 健康检查）：
 * [AgentHostService] 在 onCreate/onDestroy 更新运行状态。
 */
package com.example.autop.task.impl.host

import com.example.autop.task.AgentHostStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** AgentHost 前台服务状态实现。 */
public class AgentHostStatusImpl : AgentHostStatus {
    private val mutableRunning: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val running: StateFlow<Boolean> get() = mutableRunning

    /** 由 [AgentHostService] 生命周期调用。 */
    public fun setRunning(running: Boolean) {
        mutableRunning.value = running
    }
}
