/**
 * 端侧 embedding 资产装载（ADR-0028）：`manifest.json` 声明文件名、大小与 SHA-256；
 * 装载时逐个校验，不符即拒绝使用。与 device-impl 视觉模型的清单校验方式一致。
 *
 * 资产读取通过 [openAsset] 注入（相对于模型目录的文件名），便于纯 JVM 测试。
 */
package com.example.autop.llm.onnx

import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Outcome
import com.example.autop.llm.LlmErrorCodes
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** `manifest.json`：模型标识、维度、文件清单。 */
@Serializable
internal data class EmbeddingManifest(
    val id: String,
    val version: Int,
    val dimension: Int,
    val model: String,
    val vocab: String,
    val files: List<ManifestFile>,
) {
    /** 写入向量记录的模型标识：`id@version`（模型升级时 version 加一，触发全量重建）。 */
    val modelId: String get() = "$id@$version"
}

@Serializable
internal data class ManifestFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

/** 已校验并解析完成的模型资产。 */
internal class LoadedEmbeddingModel(
    val modelBytes: ByteArray,
    val tokenizer: WordPieceTokenizer,
)

internal class LocalModelStore(
    private val openAsset: (String) -> ByteArray?,
) {
    /** 读取并解析清单（清单很小，可在同步路径调用）。 */
    fun readManifest(): Outcome<EmbeddingManifest> {
        val raw = openAsset(MANIFEST) ?: return missing(MANIFEST)
        return runCatching {
            AutopJson.default.decodeFromString(EmbeddingManifest.serializer(), String(raw, Charsets.UTF_8))
        }.fold(
            onSuccess = { Outcome.Ok(it) },
            onFailure = { corrupt("manifest.json 无法解析：${it.message}") },
        )
    }

    /** 逐个校验清单中的文件（大小与 SHA-256），再解析词表。任一项不符即返回错误。 */
    fun load(manifest: EmbeddingManifest): Outcome<LoadedEmbeddingModel> {
        val files = manifest.files.associateBy { it.name }
        // 清单中声明的每个文件都必须存在且匹配，不只是推理用到的文件
        val verifiedFiles = HashMap<String, ByteArray>(manifest.files.size)
        for (file in manifest.files) {
            when (val r = verified(files, file.name)) {
                is Outcome.Err -> return r
                is Outcome.Ok -> verifiedFiles[file.name] = r.value
            }
        }
        val model = verifiedFiles[manifest.model] ?: return corrupt("清单未声明模型文件 ${manifest.model}")
        val vocabBytes = verifiedFiles[manifest.vocab] ?: return corrupt("清单未声明词表文件 ${manifest.vocab}")
        return runCatching { WordPieceTokenizer(parseVocab(vocabBytes)) }.fold(
            onSuccess = { Outcome.Ok(LoadedEmbeddingModel(model, it)) },
            onFailure = { corrupt("词表缺少必需的特殊符号：${it.message}") },
        )
    }

    /** 读取一个清单文件并校验大小与 SHA-256。 */
    private fun verified(
        files: Map<String, ManifestFile>,
        name: String,
    ): Outcome<ByteArray> {
        val expected = files[name] ?: return corrupt("清单未声明 $name")
        val data = openAsset(name) ?: return missing(name)
        if (data.size.toLong() != expected.bytes) {
            return corrupt("$name 大小不符：期望 ${expected.bytes}，实际 ${data.size}")
        }
        if (!sha256(data).equals(expected.sha256, ignoreCase = true)) {
            return corrupt("$name 的 SHA-256 与清单不符")
        }
        return Outcome.Ok(data)
    }

    private fun missing(name: String): Outcome.Err =
        Outcome.Err(
            AppError(
                code = LlmErrorCodes.EMBEDDING_MODEL_MISSING,
                message = "端侧 embedding 模型缺少 $name",
                hint = "重新安装应用可修复",
            ),
        )

    private fun corrupt(message: String): Outcome.Err =
        Outcome.Err(
            AppError(
                code = LlmErrorCodes.EMBEDDING_MODEL_CORRUPT,
                message = "端侧 embedding 模型校验失败：$message",
                hint = "重新安装应用可修复",
            ),
        )

    private companion object {
        const val MANIFEST = "manifest.json"
    }
}

/** 词表：每行一个词元，行号即 id（与 BERT vocab.txt 约定一致）。 */
internal fun parseVocab(bytes: ByteArray): Map<String, Int> {
    val lines = String(bytes, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }
    val tokens = if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    val vocab = HashMap<String, Int>(tokens.size * 2)
    tokens.forEachIndexed { id, token -> vocab.putIfAbsent(token, id) }
    return vocab
}

internal fun sha256(data: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(data)
        .joinToString(separator = "") { "%02x".format(it) }
