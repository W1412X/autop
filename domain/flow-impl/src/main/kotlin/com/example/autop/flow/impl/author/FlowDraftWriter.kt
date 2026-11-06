/**
 * 待确认流程草稿的写入（flows-recording-recovery.md A3、V4）：校验通过后写入 `flow_drafts`。
 * 草稿不进入流程库；用户在“流程”页确认后才可用。
 */
package com.example.autop.flow.impl.author

import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowValidationService
import com.example.autop.flow.IssueSeverity
import com.example.autop.flow.impl.db.FlowDraftEntity
import com.example.autop.flow.impl.di.FlowStorage
import javax.inject.Inject
import javax.inject.Singleton

/** 草稿写入器。 */
@Singleton
internal class FlowDraftWriter
    @Inject
    constructor(
        private val storage: FlowStorage,
        private val validation: FlowValidationService,
        private val ids: IdGenerator,
        private val clock: Clock,
    ) {
        /** 校验后写入草稿，返回草稿 id；存在错误级问题时返回错误。 */
        suspend fun save(
            definition: FlowDefinition,
            baseFlowId: String? = null,
        ): Outcome<String> {
            val errors = validation.validate(definition).filter { it.severity == IssueSeverity.ERROR }
            if (errors.isNotEmpty()) {
                val reasons = errors.joinToString("；") { it.message }
                return Outcome.Err(
                    AppError(code = FlowErrorCodes.INVALID, message = "流程校验未通过：$reasons", hint = "修正后再保存"),
                )
            }
            val draftId = ids.newId()
            storage.db.flowDao().upsertDraft(
                FlowDraftEntity(
                    draftId = draftId,
                    flowId = baseFlowId,
                    conversationJson = EMPTY_CONVERSATION,
                    draftJson = AutopJson.default.encodeToString(FlowDefinition.serializer(), definition),
                    updatedAt = clock.nowMillis(),
                ),
            )
            return Outcome.Ok(draftId)
        }

        private companion object {
            /** 草稿没有对话历史。 */
            const val EMPTY_CONVERSATION = "[]"
        }
    }
