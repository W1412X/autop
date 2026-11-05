/**
 * [ModelDirectory] 聚合实现（N164）：按 [ModelProfile.protocol] 分发到协议扩展点
 * （同 [DefaultModelProbe] 的分发方式，禁止按 baseUrl 分支）。
 */
package com.example.autop.llm.http

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.ModelDirectory
import com.example.autop.llm.ModelDirectoryFactory
import com.example.autop.llm.ModelProfile
import javax.inject.Inject

public class DefaultModelDirectory
    @Inject
    constructor(
        private val factories: Set<@JvmSuppressWildcards ModelDirectoryFactory>,
    ) : ModelDirectory {
        override suspend fun listModels(profile: ModelProfile): Outcome<List<String>> {
            val factory = factories.firstOrNull { it.protocolId == profile.protocol }
            return if (factory == null) {
                Outcome.Err(
                    AppError(
                        code = LlmErrorCodes.NOT_CONFIGURED,
                        message = "no model directory for protocol: ${profile.protocol}",
                    ),
                )
            } else {
                factory.create().listModels(profile)
            }
        }
    }
