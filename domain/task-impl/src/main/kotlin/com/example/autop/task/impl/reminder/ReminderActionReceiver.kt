/**
 * 提醒动作接收（docs/tech/07 §4.4）：执行（解锁后重新入队立即调度）/ 稍后
 * （`task.reminder.snoozeMinutes` 后再提醒）/ 跳过（Run → CANCELLED）。
 */
package com.example.autop.task.impl.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.autop.core.common.Logger
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.core.platform.DataSchemaState
import com.example.autop.task.impl.RunControl
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 提醒按钮的动作接收器。 */
@AndroidEntryPoint
public class ReminderActionReceiver : BroadcastReceiver() {
    @Inject internal lateinit var control: RunControl

    @Inject internal lateinit var configProvider: ConfigProvider

    @Inject internal lateinit var logger: Logger

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!DataSchemaState.isReady()) return
        val runId = intent.getStringExtra(EXTRA_RUN_ID)?.let { RunId(it) } ?: return
        when (intent.action) {
            ACTION_EXECUTE -> control.requeueNow(runId)
            ACTION_SNOOZE -> control.snooze(runId, configProvider.current()[TaskConfig.REMINDER].snoozeMinutes)
            ACTION_SKIP -> control.cancelWaiting(runId)
            else -> logger.w(TAG, "unexpected_action", "action" to (intent.action ?: "null"))
        }
    }

    public companion object {
        public const val ACTION_EXECUTE: String = "com.example.autop.task.reminder.EXECUTE"
        public const val ACTION_SNOOZE: String = "com.example.autop.task.reminder.SNOOZE"
        public const val ACTION_SKIP: String = "com.example.autop.task.reminder.SKIP"
        public const val EXTRA_RUN_ID: String = "runId"
        private const val TAG = "ReminderActionReceiver"
    }
}
