/**
 * `llm.embedding.provider = local` 的工厂（EmbeddingProviderFactory 扩展点，ADR-0028）。
 * 模型资产位于 `assets/embedding/bge-small-zh-v1.5-int8/`。
 */
package com.example.autop.llm.onnx

import android.content.res.AssetManager
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.llm.EmbeddingConfig
import com.example.autop.llm.EmbeddingProvider
import com.example.autop.llm.EmbeddingProviderFactory

/** 协议 id（对应 `llm.embedding.provider=local`）。 */
public const val LOCAL_EMBEDDING_PROVIDER_ID: String = "local"

public class LocalEmbeddingProviderFactory(
    private val assets: AssetManager,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : EmbeddingProviderFactory {
    override val id: String = LOCAL_EMBEDDING_PROVIDER_ID

    override fun create(config: EmbeddingConfig): EmbeddingProvider {
        val store = LocalModelStore(openAsset = { name -> readAsset(assets, name) })
        return LocalEmbeddingProvider(
            manifest = store.readManifest(),
            store = store,
            maxSeqLen = config.maxSeqLen,
            dispatchers = dispatchers,
            logger = logger,
        )
    }

    private companion object {
        const val MODEL_DIR = "embedding/bge-small-zh-v1.5-int8"

        /** 读取模型目录下的资产；缺失返回 null（由调用方映射为 `llm.embedding_model_missing`）。 */
        fun readAsset(
            assets: AssetManager,
            name: String,
        ): ByteArray? = runCatching { assets.open("$MODEL_DIR/$name").use { it.readBytes() } }.getOrNull()
    }
}
