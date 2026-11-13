/**
 * 分词与 HuggingFace tokenizers（bge-small-zh-v1.5 的 tokenizer.json）的逐条对照。
 * 参考输出由 `tokenizers` 生成，存于 `src/test/resources/tokenizer-parity.json`。
 * 词表读取随包发布的 `vocab.txt`（系统属性 `autop.embeddingDir`）。
 */
package com.example.autop.llm.onnx

import com.example.autop.core.common.AutopJson
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test
import java.io.File

class TokenizerParityTest {
    @Serializable
    private data class ParityCase(
        val text: String,
        val ids: List<Int>,
    )

    @Test
    fun `tokenizer matches the official output for every sample`() {
        val dir = File(requireNotNull(System.getProperty("autop.embeddingDir")) { "缺少系统属性 autop.embeddingDir" })
        val tokenizer = WordPieceTokenizer(parseVocab(File(dir, "vocab.txt").readBytes()))
        val cases = AutopJson.default.decodeFromString(ListSerializer(ParityCase.serializer()), loadFixture())

        assertThat(cases).isNotEmpty()
        for (case in cases) {
            val actual = tokenizer.encode(case.text, maxSeqLen = MAX_SEQ_LEN_FOR_PARITY).toList()
            assertWithMessage("ids for ${case.text}").that(actual).isEqualTo(case.ids)
        }
    }

    private fun loadFixture(): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE)) { "缺少测试资源 $FIXTURE" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private companion object {
        const val FIXTURE = "tokenizer-parity.json"

        /** 参考输出未截断，这里取一个足够大的上限。 */
        const val MAX_SEQ_LEN_FOR_PARITY = 512
    }
}
