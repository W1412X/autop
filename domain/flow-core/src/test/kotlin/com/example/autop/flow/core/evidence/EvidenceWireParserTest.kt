/**
 * 观测文本解析的验收用例（docs/design/flows-contracts-evidence §3.1）：
 * 统一 JSON → 带稳定键的节点行；解析不出即空列表（弱证据）。
 */
package com.example.autop.flow.core.evidence

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EvidenceWireParserTest {
    @Test
    fun `parses screen json into keyed rows with volatile label stripped`() {
        val text =
            """
            {"screen":{"v":1,"mode":"a11y","untrusted":true,"coord":"0-1000","fields":[],
            "windows":[{"app":"com.demo","page":"首页","active":true,"kids":[
            {"tag":"text","text":"已发送","vid":"btn1","act":["click"],"bounds":[0,0,10,10],
            "kids":[{"tag":"text","text":"12:30","bounds":[0,0,5,5]}]}]}],
            "quality":{"nodes":2,"interactive":1}}}
            """.trimIndent()

        val rows = EvidenceWireParser.parseRows(text)

        assertThat(rows).hasSize(2)
        assertThat(rows[0].text).isEqualTo("已发送")
        assertThat(rows[0].key).isNotEmpty()
        assertThat(rows[0].key).isNotEqualTo(rows[1].key)
        assertThat(rows[1].key).doesNotContain("12:30")
    }

    @Test
    fun `unstructured text yields empty rows`() {
        assertThat(EvidenceWireParser.parseRows("（llm 观测，没有结构化 JSON）")).isEmpty()
    }
}
