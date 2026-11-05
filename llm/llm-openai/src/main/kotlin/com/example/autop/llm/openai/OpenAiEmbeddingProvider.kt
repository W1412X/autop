/**
 * OpenAI 兼容 embeddings 实现（docs/tech/03 §7）：`POST {baseUrl}{embeddingsPath}`，
 * 请求体 `{"model","input":[…]}`，响应 `data[i].embedding` 按 `index` 排序；
 * 批量按 `batchSize` 切分。
 */
package com.example.autop.llm.openai

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.SecretStore
import com.example.autop.core.common.runCatchingCancellable
import com.example.autop.llm.EmbeddingConfig
import com.example.autop.llm.EmbeddingProvider
import com.example.autop.llm.EmbeddingProviderFactory
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelProfileStore
import com.example.autop.llm.http.await
import com.example.autop.llm.http.mapHttpError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** 协议 id（EmbeddingProviderFactory 扩展点，对应 `llm.embedding.provider=openai`）。 */
public const val EMBEDDING_PROVIDER_ID: String = PROTOCOL_ID

/** 依赖打包（DI 装配见 di/OpenAiModule）。 */
public class OpenAiEmbeddingContext(
    public val client: OkHttpClient,
    public val secrets: SecretStore,
    public val profiles: ModelProfileStore,
    public val quirksDefaults: OpenAiQuirksDefaults,
)

/** [EmbeddingProviderFactory] 实现：按 [id] 匹配 `llm.embedding.provider`。 */
public class OpenAiEmbeddingProviderFactory(
    private val context: OpenAiEmbeddingContext,
) : EmbeddingProviderFactory {
    override val id: String = EMBEDDING_PROVIDER_ID

    override fun create(config: EmbeddingConfig): EmbeddingProvider = OpenAiEmbeddingProvider(config, context)
}

public class OpenAiEmbeddingProvider(
    private val config: EmbeddingConfig,
    private val context: OpenAiEmbeddingContext,
) : EmbeddingProvider {
    override val modelId: String = "$EMBEDDING_PROVIDER_ID:${config.model ?: DEFAULT_MODEL_LABEL}"

    @Volatile
    private var resolvedDimension: Int = config.dimension ?: 0

    /** 未配置且未探测时为 0；首次 [embed] 后记录实际维度（docs/tech/03 §7）。 */
    override val dimension: Int
        get() = resolvedDimension

    override suspend fun embed(texts: List<String>): Outcome<List<FloatArray>> {
        val resolved = resolve()
        return when (resolved) {
            is Outcome.Err -> resolved
            is Outcome.Ok -> embedBatches(resolved.value.first, resolved.value.second, texts)
        }
    }

    private suspend fun resolve(): Outcome<Pair<ModelProfile, String>> {
        val profile = config.profileId?.let { context.profiles.get(it) }
        return if (profile == null) {
            Outcome.Err(
                AppError(
                    code = LlmErrorCodes.NOT_CONFIGURED,
                    message = "embedding profile not found: ${config.profileId}",
                ),
            )
        } else {
            val apiKey = context.secrets.get(profile.apiKeyRef)
            if (apiKey == null) {
                Outcome.Err(
                    AppError(code = LlmErrorCodes.AUTH, message = "missing api key ref: ${profile.apiKeyRef}"),
                )
            } else {
                Outcome.Ok(profile to apiKey)
            }
        }
    }

    private suspend fun embedBatches(
        profile: ModelProfile,
        apiKey: String,
        texts: List<String>,
    ): Outcome<List<FloatArray>> {
        val vectors = mutableListOf<FloatArray>()
        for (batch in texts.chunked(batchSize())) {
            when (val outcome = embedBatch(profile, apiKey, batch)) {
                is Outcome.Err -> return outcome
                is Outcome.Ok -> vectors += outcome.value
            }
        }
        if (vectors.isNotEmpty() && resolvedDimension == 0) {
            resolvedDimension = vectors.first().size
        }
        return Outcome.Ok(vectors)
    }

    private suspend fun embedBatch(
        profile: ModelProfile,
        apiKey: String,
        batch: List<String>,
    ): Outcome<List<FloatArray>> {
        val quirks = OpenAiQuirks.of(profile, context.quirksDefaults)
        val request =
            Request
                .Builder()
                .url(profile.baseUrl.trimEnd('/') + quirks.embeddingsPath)
                .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$apiKey")
                .post(requestBody(batch, profile).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        return try {
            val response = context.client.newCall(request).await()
            val snippet = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                parseEmbeddings(snippet)
            } else {
                Outcome.Err(mapHttpError(response.code, snippet, quirks.contextExceededPatterns))
            }
        } catch (e: IOException) {
            Outcome.Err(
                AppError(
                    code = LlmErrorCodes.NETWORK,
                    message = "embeddings request failed: ${e.message}",
                    cause = e,
                ),
            )
        }
    }

    private suspend fun parseEmbeddings(body: String): Outcome<List<FloatArray>> =
        runCatchingCancellable(LlmErrorCodes.BAD_RESPONSE) {
            val data =
                Json
                    .parseToJsonElement(body)
                    .jsonObject
                    .getValue("data")
                    .jsonArray
            data
                .map { it.jsonObject }
                .sortedBy { it.getValue("index").jsonPrimitive.int }
                .map { item ->
                    item
                        .getValue("embedding")
                        .jsonArray
                        .map { it.jsonPrimitive.float }
                        .toFloatArray()
                }
        }

    private fun requestBody(
        batch: List<String>,
        profile: ModelProfile,
    ): String =
        buildJsonObject {
            put("model", config.model ?: profile.model)
            put("input", JsonArray(batch.map { JsonPrimitive(it) }))
        }.toString()

    private fun batchSize(): Int = if (config.batchSize > 0) config.batchSize else 1

    private companion object {
        const val DEFAULT_MODEL_LABEL = "default"
        const val HEADER_AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
