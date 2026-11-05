/**
 * 回放样本的语义版本（ADR-0007 决策 9）：旧数据缺省 1，新采样写 3（坐标化命中）。
 */
package com.example.autop.agent

import com.example.autop.core.common.AutopJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReplayStateSampleTest {
    @Test
    fun `旧样本缺省 semanticsVersion 解码为 1`() {
        val json = """{"packageName":"com.a","activity":"Main","fingerprint":"fp","keys":["k"]}"""
        val sample = AutopJson.default.decodeFromString(ReplayStateSample.serializer(), json)
        assertThat(sample.semanticsVersion).isEqualTo(1)
    }

    @Test
    fun `新采样语义版本为 3`() {
        assertThat(REPLAY_SEMANTICS_VERSION).isEqualTo(3)
    }
}
