/**
 * 审计日志清理（docs/tech/07 §8）：保留 `task.audit.retentionDays`，
 * WorkManager 每日一次清理 `audit_log`。
 */
package com.example.autop.task.impl.store

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.autop.core.common.Clock
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.task.impl.db.TasksDb
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/** 审计清理调度与执行。 */
public class AuditCleaner(
    @ApplicationContext private val context: Context,
    private val db: TasksDb,
    private val configProvider: ConfigProvider,
    private val clock: Clock,
) {
    /** 注册每日清理（重复调用只保留一个周期任务）。 */
    public fun ensureScheduled() {
        val request =
            PeriodicWorkRequestBuilder<AuditCleanWorker>(CLEAN_INTERVAL_HOURS, TimeUnit.HOURS)
                .addTag(TAG_WORK)
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** 立即清理过期行（Worker 与手动触发共用）。 */
    public suspend fun cleanNow() {
        val days = configProvider.current()[TaskConfig.AUDIT].retentionDays
        db.auditDao().deleteOlderThan(clock.nowMillis() - days * DAY_MS)
    }

    private companion object {
        const val WORK_NAME = "audit-clean"
        const val TAG_WORK = "autop-audit-clean"
        const val CLEAN_INTERVAL_HOURS = 24L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** 每日清理 Worker。 */
public class AuditCleanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cleaner =
            EntryPointAccessors
                .fromApplication(applicationContext, AuditCleanerEntryPoint::class.java)
                .auditCleaner()
        cleaner.cleanNow()
        return Result.success()
    }
}

/** Worker 手动注入入口（Worker 非 Hilt 支持的基类，不能用 `@AndroidEntryPoint`）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AuditCleanerEntryPoint {
    fun auditCleaner(): AuditCleaner
}
