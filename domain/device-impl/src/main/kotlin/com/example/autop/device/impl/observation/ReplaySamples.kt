/**
 * 回放采样（agent-harness-overhaul WP2.8）：由已序列化的观测提取 [ReplayStateSample]，
 * 录制时的观测方式写进样本，回放按它采样。
 */
package com.example.autop.device.impl.observation

import com.example.autop.agent.REPLAY_SEMANTICS_VERSION
import com.example.autop.agent.ReplayStateSample
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.ObserveMode
import com.example.autop.device.core.SerializedScreen

/** 已序列化观测 → 回放语义样本（字段与 [ReplayStateProbeImpl] 采样同口径）。 */
internal fun sampleOf(
    screen: SerializedScreen,
    mode: ObserveMode,
    config: ConfigSnapshot,
): ReplayStateSample {
    val maxKeys = config[DeviceConfig.REPLAY].maxKeys
    return ReplayStateSample(
        packageName = screen.observation.packageName,
        activity = screen.observation.activity,
        fingerprint = screen.observation.fingerprint,
        keys =
            screen.model
                .appFlatten()
                .map { it.key }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
                .take(maxKeys),
        semanticsVersion = REPLAY_SEMANTICS_VERSION,
        observeMode = mode.id,
    )
}
