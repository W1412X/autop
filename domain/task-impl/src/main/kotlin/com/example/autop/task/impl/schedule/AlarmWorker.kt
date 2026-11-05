/**
 * 精确闹钟不可用时的降级触发（docs/tech/07 §4.1）：WorkManager 一次性任务到点后
 * 发同样的 `trigger.schedule` 事件。
 */
package com.example.autop.task.impl.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.autop.core.common.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** 降级闹钟 Worker。 */
public class AlarmWorker(
    @ApplicationContext context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @Inject internal lateinit var triggerHub: TriggerHub

    @Inject internal lateinit var clock: Clock

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(AlarmReceiver.EXTRA_TASK_ID) ?: return Result.failure()
        triggerHub.onScheduleFired(
            com.example.autop.core.common
                .TaskId(taskId),
            clock.nowMillis(),
        )
        return Result.success()
    }
}
