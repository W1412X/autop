/**
 * [ReplayStateProbe] 实现（docs/tech/04 §2.7、05 §13，R1/A6）：抓取当前屏幕并生成语义样本
 * （packageName / activity / fingerprint / keys），供回放前比对与 [ActionEpilogue] 的等待稳定采样。
 */
package com.example.autop.device.impl.observation

import com.example.autop.agent.REPLAY_SEMANTICS_VERSION
import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayStateSample
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.ObserveMode
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.impl.SnapshotSerializer
import javax.inject.Inject
import javax.inject.Singleton

/** 屏幕语义采样：不进差量基线、不动 Run 的观测历史，只读当前屏。 */
@Singleton
public class ReplayStateProbeImpl
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val serializer: SnapshotSerializer,
        private val configProvider: ConfigProvider,
    ) : ReplayStateProbe {
        override suspend fun sample(): Outcome<ReplayStateSample> =
            when (val got = screenReader.snapshot()) {
                is Outcome.Err -> got
                is Outcome.Ok -> Outcome.Ok(sampleOf(got.value))
            }

        private fun sampleOf(snap: ScreenSnapshot): ReplayStateSample {
            val config = configProvider.current()
            val screen = serializer.serialize(snap, ObserveMode.A11Y, config, listOf(ObserveMode.A11Y))
            val maxKeys = config[DeviceConfig.REPLAY].maxKeys
            return ReplayStateSample(
                packageName = screen.observation.packageName,
                activity = screen.observation.activity,
                fingerprint = screen.observation.fingerprint,
                semanticsVersion = REPLAY_SEMANTICS_VERSION,
                keys =
                    screen.model
                        .appFlatten()
                        .map { it.key }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sorted()
                        .take(maxKeys),
            )
        }
    }
