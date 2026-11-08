/**
 * [FlowProgressFeed] 实现：登记表（根 Run → 句柄+进度），收集 [FlowRunHandle.progress]
 * 直到运行结束自动注销；cancel 只动流程句柄，不动根 Run。
 */
package com.example.autop.flow.impl.run

import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.RunId
import com.example.autop.flow.FlowProgressFeed
import com.example.autop.flow.FlowRunHandle
import com.example.autop.flow.FlowRunProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 流程进度登记表（进程级）。 */
@Singleton
public class FlowProgressFeedImpl
    @Inject
    constructor(
        dispatchers: DispatcherProvider,
    ) : FlowProgressFeed {
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

        private val entries = mutableMapOf<String, Entry>()

        private val _active = MutableStateFlow<Map<String, FlowRunProgress>>(emptyMap())

        override val active: StateFlow<Map<String, FlowRunProgress>> = _active.asStateFlow()

        private class Entry(
            val handle: FlowRunHandle,
            var flowName: String,
        )

        @Synchronized
        override fun register(
            rootRunId: RunId,
            flowName: String,
            handle: FlowRunHandle,
        ) {
            entries[rootRunId.value] = Entry(handle, flowName)
            scope.launch {
                handle.progress.collect { progress ->
                    _active.update { it + (rootRunId.value to FlowRunProgress(flowName, progress)) }
                }
            }
            scope.launch {
                runCatching { handle.await() }
                unregister(rootRunId)
            }
        }

        @Synchronized
        override fun cancel(rootRunId: RunId): Boolean {
            val handle = entries[rootRunId.value]?.handle ?: return false
            handle.cancel()
            return true
        }

        @Synchronized
        private fun unregister(rootRunId: RunId) {
            entries.remove(rootRunId.value)
            _active.update { it - rootRunId.value }
        }
    }
