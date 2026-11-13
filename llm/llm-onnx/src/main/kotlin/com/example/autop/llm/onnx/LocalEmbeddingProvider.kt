/**
 * 端侧 embedding（ADR-0028）：bge-small-zh-v1.5 int8，ONNX Runtime CPU 推理，输出 `sentence_embedding`
 * （512 维，已归一化）。注册为 `llm.embedding.provider = local`。
 *
 * - 清单在构造时同步读取（提供 modelId 与维度，清单很小）；
 * - 模型字节、校验与会话在第一次 [embed] 时惰性装载并缓存，装载失败也缓存，避免每次向量化都重算校验；
 * - 推理与装载共用一把锁（单会话串行），CPU 工作切到 `dispatchers.default`。
 */
package com.example.autop.llm.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import com.example.autop.core.common.AppError
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.llm.EmbeddingProvider
import com.example.autop.llm.LlmErrorCodes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt

public class LocalEmbeddingProvider internal constructor(
    private val manifest: Outcome<EmbeddingManifest>,
    private val store: LocalModelStore,
    private val maxSeqLen: Int,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : EmbeddingProvider {
    override val modelId: String = (manifest as? Outcome.Ok)?.value?.modelId ?: UNAVAILABLE_MODEL_ID

    override val dimension: Int = (manifest as? Outcome.Ok)?.value?.dimension ?: 0

    private val lock = Mutex()

    /** 装载结果（成功或失败都缓存）；只在 [lock] 内访问。 */
    private var runtime: Outcome<Runtime>? = null

    override suspend fun embed(texts: List<String>): Outcome<List<FloatArray>> {
        if (texts.isEmpty()) return Outcome.Ok(emptyList())
        return lock.withLock {
            when (val rt = runtimeOrLoad()) {
                is Outcome.Err -> rt
                is Outcome.Ok -> infer(rt.value, texts)
            }
        }
    }

    private fun runtimeOrLoad(): Outcome<Runtime> {
        runtime?.let { return it }
        return load().also { runtime = it }
    }

    private fun load(): Outcome<Runtime> {
        val m =
            when (manifest) {
                is Outcome.Err -> return manifest
                is Outcome.Ok -> manifest.value
            }
        val model =
            when (val r = store.load(m)) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
        return runCatching { createSession(model.modelBytes) }.fold(
            onSuccess = { Outcome.Ok(Runtime(it, model.tokenizer)) },
            onFailure = {
                logger.e(TAG, "session_create_failed", error = it)
                Outcome.Err(AppError(LlmErrorCodes.EMBEDDING_FAILED, "端侧 embedding 会话创建失败：${it.message}"))
            },
        )
    }

    private fun createSession(bytes: ByteArray): OrtSession {
        val options =
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(
                    OrtSession.SessionOptions.OptLevel.ALL_OPT,
                )
            }
        return OrtEnvironment.getEnvironment().createSession(bytes, options)
    }

    private suspend fun infer(
        rt: Runtime,
        texts: List<String>,
    ): Outcome<List<FloatArray>> =
        withContext(dispatchers.default) {
            try {
                val encodings = texts.map { rt.tokenizer.encode(it, maxSeqLen) }
                val batch = padBatch(encodings, rt.tokenizer.padId)
                runSession(rt.session, batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OrtException) {
                logger.w(TAG, "embed_failed", error = e)
                Outcome.Err(
                    AppError(LlmErrorCodes.EMBEDDING_FAILED, "端侧 embedding 推理失败：${e.message}", retryable = true),
                )
            }
        }

    private fun runSession(
        session: OrtSession,
        batch: EncodedBatch,
    ): Outcome<List<FloatArray>> {
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(batch.batch.toLong(), batch.seqLen.toLong())
        val inputs = HashMap<String, OnnxTensor>()
        try {
            inputs[INPUT_IDS] = OnnxTensor.createTensor(env, LongBuffer.wrap(batch.inputIds), shape)
            inputs[ATTENTION_MASK] = OnnxTensor.createTensor(env, LongBuffer.wrap(batch.attentionMask), shape)
            inputs[TOKEN_TYPE_IDS] = OnnxTensor.createTensor(env, LongBuffer.wrap(batch.tokenTypeIds), shape)
            session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val rows = result.get(OUTPUT).get().value as Array<FloatArray>
                if (rows.size != batch.batch) {
                    return Outcome.Err(AppError(LlmErrorCodes.EMBEDDING_FAILED, "输出行数与输入不符：${rows.size}"))
                }
                return Outcome.Ok(rows.map { it.normalized() })
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** 装载完成的推理运行时：会话与分词器。 */
    private class Runtime(
        val session: OrtSession,
        val tokenizer: WordPieceTokenizer,
    )

    private companion object {
        const val TAG = "LocalEmbedding"
        const val UNAVAILABLE_MODEL_ID = "local:unavailable"
        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val TOKEN_TYPE_IDS = "token_type_ids"
        const val OUTPUT = "sentence_embedding"
    }
}

/** L2 归一化（模型输出已归一化，这里是防御性处理；零向量保持不变）。 */
internal fun FloatArray.normalized(): FloatArray {
    var sumSq = 0.0
    for (v in this) sumSq += v.toDouble() * v
    val norm = sqrt(sumSq)
    if (norm == 0.0) return this
    return FloatArray(size) { (this[it] / norm).toFloat() }
}
