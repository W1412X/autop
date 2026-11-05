/**
 * Anthropic 兼容服务的模型列表（N164）：GET {baseUrl}{modelsPath}（默认 /v1/models，
 * 与 messagesPath 同风格）→ 解析 `data[].id`。
 */
package com.example.autop.llm.anthropic

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.ModelDirectory
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.http.await
import com.example.autop.llm.http.mapHttpError
import com.example.autop.llm.http.parseModelIds
import okhttp3.Request
import java.io.IOException

public class AnthropicModelDirectory(
    private val context: AnthropicContext,
) : ModelDirectory {
    override suspend fun listModels(profile: ModelProfile): Outcome<List<String>> {
        val quirks = AnthropicQuirks.of(profile, context.quirksDefaults)
        val apiKey = context.secrets.get(profile.apiKeyRef)
        if (apiKey == null) {
            return Outcome.Err(
                AppError(code = LlmErrorCodes.AUTH, message = "missing api key ref '${profile.apiKeyRef}'"),
            )
        }
        val httpRequest =
            Request
                .Builder()
                .url(profile.baseUrl.trimEnd('/') + modelsPath(profile))
                .header("x-api-key", apiKey)
                .header("anthropic-version", context.quirksDefaults.apiVersion)
                .apply { profile.extraHeaders.forEach { (name, value) -> header(name, value) } }
                .get()
                .build()
        return try {
            context.client.newCall(httpRequest).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    parseModelIds(body)
                } else {
                    Outcome.Err(mapHttpError(response.code, body, quirks.contextExceededPatterns))
                }
            }
        } catch (e: IOException) {
            Outcome.Err(
                AppError(
                    code = LlmErrorCodes.NETWORK,
                    message = e.message ?: e.toString(),
                    retryable = true,
                    cause = e,
                ),
            )
        }
    }

    private fun modelsPath(profile: ModelProfile): String =
        profile.quirks[AnthropicQuirkKeys.MODELS_PATH] ?: DEFAULT_MODELS_PATH
}

private const val DEFAULT_MODELS_PATH = "/v1/models"
