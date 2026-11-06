package com.example.autop.agent.runtime

import com.example.autop.agent.RunLineage
import com.example.autop.core.common.RunId
import java.util.concurrent.ConcurrentHashMap

/** 进程内的父子 Run 登记表；Run 结束后由发起方注销。 */
internal class DefaultRunLineage : RunLineage {
    private val parents = ConcurrentHashMap<RunId, RunId>()

    override fun register(
        child: RunId,
        parent: RunId,
    ) {
        parents[child] = parent
    }

    override fun rootOf(runId: RunId): RunId {
        var current = runId
        // 链长有限（流程嵌套每层一条）；visited 防止错误登记造成环。
        val seen = HashSet<RunId>()
        while (seen.add(current)) {
            current = parents[current] ?: return current
        }
        return current
    }

    override fun unregister(child: RunId) {
        parents.remove(child)
    }
}
