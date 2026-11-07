/**
 * 流程修订的落地（docs/design/flows-precondition-selfrepair.md S2/S3）。
 *
 * 一次修订要做两件事：旧定义进版本库（可回滚）、新定义写主表，并推给正在运行的实例
 * （当前 Run 的后续步骤用新定义继续）。版本留存在写主表之前，保证任何一步失败都还有旧定义可查。
 */
package com.example.autop.flow.impl.service

import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowSession
import com.example.autop.flow.StoredFlow
import com.example.autop.flow.impl.store.RoomFlowStore

/** 修订落地服务。 */
public class FlowRevisionService
    internal constructor(
        private val flows: RoomFlowStore,
        private val session: () -> FlowSession,
        private val logger: Logger,
    ) {
        /**
         * 应用一次修订。
         *
         * [flowId] 目标流程；[definition] 修订后的完整定义（结构必须与当前定义一致）；
         * [reason] 写进版本记录的原因。
         */
        suspend fun apply(
            flowId: String,
            definition: FlowDefinition,
            reason: String?,
        ): Outcome<StoredFlow> {
            val saved = flows.save(definition, reason)
            if (saved is Outcome.Err) return saved
            // 推给正在运行的实例：推不进去不影响落库（实例可能已结束，或修订后的结构与运行中不符）。
            when (val pushed = session().applyPatch(flowId, definition)) {
                is Outcome.Err ->
                    logger.w(
                        TAG,
                        "flow_patch_push_failed",
                        "flow" to flowId,
                        "reason" to pushed.error.message,
                    )
                is Outcome.Ok -> Unit
            }
            return saved
        }

        private companion object {
            const val TAG = "FlowRevision"
        }
    }
