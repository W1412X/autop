/**
 * 试运行（docs/tech/08 §4.3）：用最近事件验证条件任务，
 * 评估复用 [TriggerHub.evaluate]（不过闸门记录、不入队）。
 */
package com.example.autop.task.impl.schedule

import com.example.autop.device.NotificationFeed
import com.example.autop.task.ConditionResult
import com.example.autop.task.Task
import com.example.autop.task.TriggerEvent
import com.example.autop.task.TriggerSimulator
import com.example.autop.task.impl.sources.NotificationTriggerSource

/** 试运行实现。 */
public class TriggerSimulatorImpl(
    private val feed: NotificationFeed,
    private val hub: TriggerHub,
) : TriggerSimulator {
    override suspend fun recentEvents(
        sourceId: String,
        limit: Int,
    ): List<TriggerEvent> =
        if (sourceId == NotificationTriggerSource.ID) {
            feed.recent(packageName = null, sinceMs = null, limit = limit).map { NotificationTriggerSource.eventOf(it) }
        } else {
            emptyList()
        }

    override suspend fun evaluate(
        task: Task,
        event: TriggerEvent,
    ): ConditionResult {
        val evaluation = hub.evaluate(task, event)
        evaluation.judged?.let { return it }
        // 闸门拦截（冷却/上限/重复）：规则其实匹配但本次不会触发——按不匹配报，原因交 UI 解释，
        // 否则会显示“匹配，可以触发 · COOLDOWN”这种自相矛盾的结果。
        if (evaluation.gate != null) return ConditionResult(match = false, reason = evaluation.gate)
        return ConditionResult(match = evaluation.filter.match, reason = null)
    }
}
