/**
 * AgentHost 前台服务拉起端口（docs/tech/07 §4.5）：Run 入队时保证保活与通知就位。
 */
package com.example.autop.task.impl.host

import android.content.Context
import android.content.Intent
import com.example.autop.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext

/** 拉起 AgentHostService。 */
public interface AgentHostStarter {
    /** 确保前台服务在运行（已在运行时无操作）。 */
    public fun ensureRunning()
}

/** 默认实现：`startForegroundService`；后台启动受限时记日志（通知缺失不阻断 Run）。 */
public class AgentHostStarterImpl(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : AgentHostStarter {
    @Suppress("TooGenericExceptionCaught") // 启动失败原因多样（后台限制/ANR 预防），统一兜底记日志
    override fun ensureRunning() {
        try {
            context.startForegroundService(Intent(context, AgentHostService::class.java))
        } catch (e: Exception) {
            logger.w(TAG, "start_host_failed", "error" to (e.message ?: e.toString()))
        }
    }

    private companion object {
        const val TAG = "AgentHostStarter"
    }
}
