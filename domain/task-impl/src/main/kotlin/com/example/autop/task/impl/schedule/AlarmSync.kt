/**
 * 定时任务闹钟同步（2026-11-09 修复"到点不执行"）：订阅 [TaskStore.scheduleChanges]
 * （保存/启用/禁用/删除时 bump）并对全部启用任务重排闹钟——此前该信号无任何订阅者，
 * 新建/编辑的定时任务在重启或系统时间变更前根本没注册闹钟。StateFlow 首值即触发
 * 一次全量重排，天然覆盖应用启动。
 */
package com.example.autop.task.impl.schedule

import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.task.impl.store.TaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 闹钟与任务变更的同步器（进程级；幂等重排，schedule 内部按同一 PendingIntent 覆盖）。 */
@Singleton
public class AlarmSync
    @Inject
    constructor(
        private val taskStore: TaskStore,
        private val scheduler: AlarmScheduler,
        private val logger: Logger,
    ) {
        private var started = false

        /** 启动同步（幂等）：首值全量重排，之后每次任务变更重排。 */
        @Synchronized
        public fun start(scope: CoroutineScope) {
            if (started) return
            started = true
            scope.launch(logger.exceptionHandler("AlarmSync")) {
                taskStore.scheduleChanges.collect {
                    val result =
                        runCatching { scheduler.rescheduleAll(taskStore.enabledTasks()) }
                    result.exceptionOrNull()?.let { error ->
                        logger.e(tag = TAG, event = "reschedule_failed", error = error)
                    }
                }
            }
        }

        private companion object {
            const val TAG = "AlarmSync"
        }
    }
