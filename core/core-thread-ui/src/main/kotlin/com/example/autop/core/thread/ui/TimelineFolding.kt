/**
 * 时间线折叠（docs/tech/08 §4.2 StepGroup 折叠规则）：[mergeTimeline]
 * 的收尾阶段，同包 internal 纯函数。
 */
package com.example.autop.core.thread.ui

/**
 * 链路编号：按时间线顺序给步骤行编号（折叠组携带已编号步骤，docs/tech/08 §4.2，N172），
 * 与 [foldGroups] 同为归并收尾阶段。
 */
internal fun numberSteps(entries: List<TimelineEntry>): List<TimelineEntry> {
    var no = 0
    return entries.map { entry ->
        val step = entry.item as? TimelineItem.StepRow ?: return@map entry
        no += 1
        entry.copy(item = step.copy(stepNo = no))
    }
}

/** 连续 ≥ [threshold] 个成功读类步骤折叠为 [TimelineItem.StepGroup]。 */
internal fun foldGroups(
    entries: List<TimelineEntry>,
    threshold: Int,
): List<TimelineEntry> {
    val result = mutableListOf<TimelineEntry>()
    var runStart = -1
    entries.forEachIndexed { index, entry ->
        if (isReadStep(entry)) {
            if (runStart < 0) {
                runStart = index
            }
        } else {
            flushRun(entries, result, runStart, index, threshold)
            runStart = -1
            result.add(entry)
        }
    }
    flushRun(entries, result, runStart, entries.size, threshold)
    return result
}

private fun flushRun(
    entries: List<TimelineEntry>,
    result: MutableList<TimelineEntry>,
    start: Int,
    end: Int,
    threshold: Int,
) {
    if (start < 0) return
    val run = entries.subList(start, end)
    if (run.size >= threshold && threshold > 1) {
        val steps = run.map { it.item as TimelineItem.StepRow }
        result.add(TimelineEntry(TimelineItem.StepGroup(steps), run.last().seq, run.last().forkable))
    } else {
        result.addAll(run)
    }
}

private fun isReadStep(entry: TimelineEntry): Boolean {
    val step = entry.item as? TimelineItem.StepRow ?: return false
    return step.state == StepState.DONE && step.toolId in READ_TOOLS
}
