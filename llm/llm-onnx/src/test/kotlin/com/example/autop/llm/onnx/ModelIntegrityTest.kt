/**
 * 随包模型资产的装载校验（纯 JVM，不触发 ONNX 会话）：清单可解析、声明的每个文件的大小与 SHA-256 都匹配，
 * 篡改或缺失时返回对应的错误码。
 */
package com.example.autop.llm.onnx

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.llm.LlmErrorCodes
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class ModelIntegrityTest {
    private val dir = File(requireNotNull(System.getProperty("autop.embeddingDir")) { "缺少系统属性 autop.embeddingDir" })

    private fun store(
        tamper: ((String, ByteArray) -> ByteArray)? = null,
        missing: String? = null,
    ) = LocalModelStore { name ->
        if (name == missing) {
            null
        } else {
            File(dir, name).takeIf { it.isFile }?.readBytes()?.let { bytes -> tamper?.invoke(name, bytes) ?: bytes }
        }
    }

    @Test
    fun `shipped manifest identifies the model and every declared file matches`() {
        val manifest = store().readManifest().getOrNull()!!

        assertThat(manifest.modelId).isEqualTo("local:bge-small-zh-v1.5-int8@1")
        assertThat(manifest.dimension).isEqualTo(512)
        assertThat(store().load(manifest)).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun `a single flipped byte in the model is rejected as corrupt`() {
        val manifest = store().readManifest().getOrNull()!!
        val tampered =
            store(tamper = { name, bytes ->
                if (name == manifest.model) bytes.also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } else bytes
            })

        val result = tampered.load(manifest)

        assertThat((result as Outcome.Err).error.code).isEqualTo(LlmErrorCodes.EMBEDDING_MODEL_CORRUPT)
    }

    @Test
    fun `a missing model file is reported as missing`() {
        val manifest = store().readManifest().getOrNull()!!

        val result = store(missing = manifest.model).load(manifest)

        assertThat((result as Outcome.Err).error.code).isEqualTo(LlmErrorCodes.EMBEDDING_MODEL_MISSING)
    }
}
