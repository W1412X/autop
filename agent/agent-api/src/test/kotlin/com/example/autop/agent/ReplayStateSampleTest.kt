/**
 * 回放样本的语义版本（ADR-0007 决策 9；agent-harness-overhaul WP2.8 升到 4）：
 * 旧数据缺省 1；缺 observeMode 的旧脚本不再兼容，读取方按“无脚本”处理。
 */
package com.example.autop.agent

import com.example.autop.core.common.AutopJson
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ReplayStateSampleTest {
    @Test
    fun `旧样本缺 observeMode 解码失败（读取方按无脚本处理）`() {
        val json = """{"packageName":"com.a","activity":"Main","fingerprint":"fp","keys":["k"]}"""
        val e =
            assertThrows(kotlinx.serialization.SerializationException::class.java) {
                AutopJson.default.decodeFromString(ReplayStateSample.serializer(), json)
            }
        assertThat(e).hasMessageThat().contains("observeMode")
    }

    @Test
    fun `新采样语义版本为 4 并带观测方式`() {
        assertThat(REPLAY_SEMANTICS_VERSION).isEqualTo(4)
        val json =
            """{"packageName":"com.a","activity":"Main","fingerprint":"fp","keys":["k"],"observeMode":"visual"}"""
        val sample = AutopJson.default.decodeFromString(ReplayStateSample.serializer(), json)
        assertThat(sample.observeMode).isEqualTo("visual")
    }
}
