/**
 * 闹钟与系统事件接收（docs/tech/07 §4.1）：闹钟到点发 `trigger.schedule` 事件给
 * TriggerHub 并安排下一次；BOOT_COMPLETED/TIME_SET/TIMEZONE_CHANGED/MY_PACKAGE_REPLACED
 * 触发全部重排。
 */
package com.example.autop.task.impl.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Logger
import com.example.autop.core.common.TaskId
import com.example.autop.core.platform.DataSchemaState
import com.example.autop.task.impl.di.AppScope
import com.example.autop.task.impl.store.TaskStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 闹钟到点与重排事件接收器。 */
@AndroidEntryPoint
public class AlarmReceiver : BroadcastReceiver() {
    @Inject internal lateinit var triggerHub: TriggerHub

    @Inject internal lateinit var taskStore: TaskStore

    @Inject internal lateinit var scheduler: AlarmScheduler

    @Inject internal lateinit var clock: Clock

    @Inject internal lateinit var logger: Logger

    @Inject
    @AppScope
    internal lateinit var scope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!DataSchemaState.isReady()) return
        val pending = goAsync()
        scope.launch {
            try {
                handle(intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_SCHEDULED -> fireScheduled(intent.getStringExtra(EXTRA_TASK_ID), intent.getLongExtra(EXTRA_AT, 0))
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> rescheduleAll()
            else -> logger.w(TAG, "unexpected_action", "action" to (intent.action ?: "null"))
        }
    }

    private suspend fun fireScheduled(
        taskId: String?,
        at: Long,
    ) {
        if (taskId == null) return
        triggerHub.onScheduleFired(TaskId(taskId), at)
        // 下一次由 TriggerHub/TaskStore 在事件消费后重排；这里兜底安排一次。
        taskStore.get(TaskId(taskId))?.let { scheduler.schedule(it) }
    }

    private suspend fun rescheduleAll() {
        scheduler.rescheduleAll(taskStore.enabledTasks())
    }

    public companion object {
        public const val ACTION_SCHEDULED: String = "com.example.autop.task.ACTION_SCHEDULED"
        public const val EXTRA_TASK_ID: String = "taskId"
        public const val EXTRA_AT: String = "at"
        private const val TAG = "AlarmReceiver"
    }
}
