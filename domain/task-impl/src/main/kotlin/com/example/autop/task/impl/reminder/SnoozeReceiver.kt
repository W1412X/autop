/**
 * “稍后”到期重提醒（docs/tech/07 §4.4）：snooze 到点后重新展示锁屏提醒；
 * 带 [EXTRA_EXPIRE] 时改为超时收口（Run → EXPIRED）。
 */
package com.example.autop.task.impl.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.autop.core.common.RunId
import com.example.autop.task.RunStatus
import com.example.autop.task.impl.RunScheduler
import com.example.autop.task.impl.di.AppScope
import com.example.autop.task.impl.store.RunStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 稍后到期接收器。 */
@AndroidEntryPoint
public class SnoozeReceiver : BroadcastReceiver() {
    @Inject internal lateinit var runStore: RunStore

    @Inject internal lateinit var lockReminder: LockReminder

    @Inject internal lateinit var scheduler: RunScheduler

    @Inject
    @AppScope
    internal lateinit var scope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID)?.let { RunId(it) } ?: return
        val expire = intent.getBooleanExtra(EXTRA_EXPIRE, false)
        val pending = goAsync()
        scope.launch {
            try {
                if (expire) {
                    scheduler.cancelWaitingRun(runId, RunStatus.EXPIRED)
                } else {
                    val record = runStore.get(runId) ?: return@launch
                    if (record.status == RunStatus.WAITING_USER) lockReminder.remind(record)
                }
            } finally {
                pending.finish()
            }
        }
    }

    public companion object {
        /** RunId extra（与 ReminderActionReceiver.EXTRA_RUN_ID 同名同值）。 */
        public const val EXTRA_RUN_ID: String = "runId"

        /** true = 提醒超时收口。 */
        public const val EXTRA_EXPIRE: String = "expire"
    }
}
