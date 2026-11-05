/**
 * 定时任务的闹钟安排（docs/tech/07 §4.1）：Once → atMillis（已过去不安排）；
 * Cron → [CronExpression.next]。`canScheduleExactAlarms()` 为 false（31–32）时
 * 降级为 WorkManager 的 OneTimeWorkRequest（任务显示“可能不准时”）。
 */
package com.example.autop.task.impl.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Logger
import com.example.autop.core.common.TaskId
import com.example.autop.task.ScheduleSpec
import com.example.autop.task.Task
import com.example.autop.task.Trigger
import com.example.autop.task.core.Cron
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId

/** 闹钟安排。 */
public class AlarmScheduler(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
    private val logger: Logger,
) {
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 当前是否只能非精确触发（列表据此显示“可能不准时”）。 */
    public fun exactAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** 计算任务下一次触发时间（纯计算，不安排）。 */
    public fun nextFireAt(task: Task): Long? {
        val trigger = task.trigger as? Trigger.Schedule ?: return null
        val now = clock.nowMillis()
        return when (val spec = trigger.spec) {
            is ScheduleSpec.Once -> spec.atMillis.takeIf { it > now }
            is ScheduleSpec.Cron ->
                runCatching { Cron.parse(spec.expr) }
                    .getOrNull()
                    ?.next(now, runCatching { ZoneId.of(spec.zoneId) }.getOrDefault(ZoneId.systemDefault()))
        }
    }

    /** 安排（或重排）任务的下一次触发；返回触发时刻（null = 不安排）。 */
    public fun schedule(task: Task): Long? {
        val at = if (task.enabled) nextFireAt(task) else null
        if (at != null) {
            val pending = pendingIntent(task.id)
            if (exactAllowed()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                logger.w(TAG, "exact_alarm_unavailable", "taskId" to task.id.value)
                scheduleInexact(task, at)
            }
        }
        return at
    }

    /** 降级：WorkManager 一次性任务（docs/tech/07 §4.1）。 */
    private fun scheduleInexact(
        task: Task,
        at: Long,
    ) {
        val delay = (at - clock.nowMillis()).coerceAtLeast(0)
        val request =
            OneTimeWorkRequestBuilder<AlarmWorker>()
                .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(AlarmReceiver.EXTRA_TASK_ID to task.id.value))
                .addTag(TAG_WORK)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(task.id), ExistingWorkPolicy.REPLACE, request)
    }

    /** 取消安排。 */
    public fun cancel(taskId: TaskId) {
        alarmManager.cancel(pendingIntent(taskId))
        WorkManager.getInstance(context).cancelUniqueWork(workName(taskId))
    }

    /** 全部重排（保存/启用/禁用/删除、BOOT_COMPLETED、TIME_SET、TIMEZONE_CHANGED、升级后调用）。 */
    public fun rescheduleAll(tasks: List<Task>) {
        tasks.forEach { schedule(it) }
    }

    private fun pendingIntent(taskId: TaskId): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.value.hashCode(),
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_SCHEDULED)
                .putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId.value),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun workName(taskId: TaskId): String = "$WORK_PREFIX${taskId.value}"

    private companion object {
        const val TAG_WORK = "autop-alarm"
        const val WORK_PREFIX = "alarm-"
        const val TAG = "AlarmScheduler"
    }
}
